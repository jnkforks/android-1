/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.profilers.cpu;

import com.android.tools.adtui.model.Range;
import com.android.tools.profiler.proto.Common;
import com.android.tools.profiler.proto.Cpu;
import com.android.tools.profiler.proto.Cpu.CpuTraceInfo;
import com.android.tools.profiler.proto.Cpu.CpuTraceType;
import com.android.tools.profiler.proto.CpuProfiler.CpuStartRequest;
import com.android.tools.profiler.proto.CpuProfiler.CpuStopRequest;
import com.android.tools.profiler.proto.CpuProfiler.GetTraceInfoRequest;
import com.android.tools.profiler.proto.CpuProfiler.GetTraceInfoResponse;
import com.android.tools.profiler.proto.Transport;
import com.android.tools.profilers.ProfilerClient;
import com.android.tools.profilers.ProfilerMonitor;
import com.android.tools.profilers.ProfilerTimeline;
import com.android.tools.profilers.StudioProfiler;
import com.android.tools.profilers.StudioProfilers;
import com.android.tools.profilers.cpu.atrace.AtraceExporter;
import com.android.tools.profilers.sessions.SessionsManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CpuProfiler extends StudioProfiler {

  /**
   * Maps a {@link Common.Session} ID to the trace {@link File} (to be) imported into it as a {@link CpuCapture}.
   */
  @NotNull
  private final Map<Long, File> mySessionTraceFiles;

  public CpuProfiler(@NotNull StudioProfilers profilers) {
    super(profilers);
    mySessionTraceFiles = new HashMap<>();
    if (profilers.getIdeServices().getFeatureConfig().isImportCpuTraceEnabled()) {
      // Only enable handling *.trace files if the import CPU traces flag is enabled.
      registerImportedSessionListener();
      registerTraceImportHandler();
    }
  }

  private void run() {
    Common.Session session = myProfilers.getSession();
    // Make sure the timeline is paused when the stage is opened for the first time, and its bounds are within the session.
    ProfilerTimeline timeline = myProfilers.getTimeline();
    timeline.reset(session.getStartTimestamp(), session.getEndTimestamp());
    timeline.setIsPaused(true);

    assert mySessionTraceFiles.containsKey(session.getSessionId());
    if (myProfilers.getIdeServices().getFeatureConfig().isCpuCaptureStageEnabled()) {
      myProfilers.setStage(CpuCaptureStage.create(myProfilers, "Imported", mySessionTraceFiles.get(session.getSessionId())));
    }
    else {
      myProfilers.setStage(new CpuProfilerStage(myProfilers, mySessionTraceFiles.get(session.getSessionId())));
    }
  }

  private static Logger getLogger() {
    return Logger.getInstance(CpuProfiler.class);
  }

  /**
   * Registers a listener that will open a {@link CpuProfilerStage} in import trace mode when the {@link Common.Session} selected is a
   * {@link Common.SessionMetaData.SessionType#CPU_CAPTURE}.
   */
  private void registerImportedSessionListener() {
    myProfilers.registerSessionChangeListener(Common.SessionMetaData.SessionType.CPU_CAPTURE, this::run);
  }

  /**
   * Registers a handler for importing *.trace files. It will create a {@link Common.SessionMetaData.SessionType#CPU_CAPTURE}
   * {@link Common.Session} and select it.
   */
  private void registerTraceImportHandler() {
    SessionsManager sessionsManager = myProfilers.getSessionsManager();
    sessionsManager.registerImportHandler("trace", file -> {
      long startTimestampEpochMs = System.currentTimeMillis();
      long startTimestampNs = getImportedSessionStartTimestampNs(file, startTimestampEpochMs);
      // The end timestamp is going to be updated once the capture is parsed. When starting the session (before parsing a trace), set it to
      // be one minute from the begin time, as it is a reasonable length for a "default" timeline that can be displayed if parsing fails
      // and before the parsing happens.
      long endTimestampNs = startTimestampNs + TimeUnit.MINUTES.toNanos(1);
      if (myProfilers.getIdeServices().getFeatureConfig().isUnifiedPipelineEnabled()) {
        sessionsManager.createImportedSession(file.getName(),
                                              Common.SessionData.SessionStarted.SessionType.CPU_CAPTURE,
                                              startTimestampNs,
                                              endTimestampNs,
                                              startTimestampEpochMs,
                                              Collections.emptyMap());
        // NOTE - New imported session will be auto selected by SessionsManager once it is queried

        // TODO b/132796215 use the shared byte cache instead of storing the file locally, as this CpuProfiler instance does not persist
        // across projects.
        mySessionTraceFiles.put(startTimestampNs, file);
      }
      else {
        Common.Session importedSession = sessionsManager.createImportedSessionLegacy(file.getName(),
                                                                                     Common.SessionMetaData.SessionType.CPU_CAPTURE,
                                                                                     startTimestampNs,
                                                                                     endTimestampNs,
                                                                                     startTimestampEpochMs);
        // Associate the trace file with the session so we can retrieve it later.
        mySessionTraceFiles.put(importedSession.getSessionId(), file);
        // Select the imported session
        sessionsManager.update();
        sessionsManager.setSession(importedSession);
      }

      myProfilers.getIdeServices().getFeatureTracker().trackCreateSession(Common.SessionMetaData.SessionType.CPU_CAPTURE,
                                                                          SessionsManager.SessionCreationSource.MANUAL);
    });
  }

  /**
   * Returns the start timestamp, in nanoseconds, of the imported trace session. First, we try to get the trace file creation time.
   * If there is an error to obtain it, we fallback to the session start time.
   */
  private static long getImportedSessionStartTimestampNs(File trace, long sessionStartTimestampEpochMs) {
    Path tracePath = Paths.get(trace.getPath());
    try {
      BasicFileAttributes attributes = Files.readAttributes(tracePath, BasicFileAttributes.class);
      return attributes.creationTime().to(TimeUnit.NANOSECONDS);
    }
    catch (IOException e) {
      getLogger().warn("Trace file creation time could not be read. Falling back to session start time.");
    }

    // If the file creation time can not be obtained, use the session epoch start time as a fallback
    return TimeUnit.MICROSECONDS.toNanos(sessionStartTimestampEpochMs);
  }

  @Nullable
  public File getTraceFile(Common.Session session) {
    return mySessionTraceFiles.get(session.getSessionId());
  }

  @Override
  public ProfilerMonitor newMonitor() {
    return new CpuMonitor(myProfilers);
  }

  @Override
  public void startProfiling(Common.Session session) {
    // TODO: handle different status of the response
    myProfilers.getClient().getCpuClient().startMonitoringApp(CpuStartRequest.newBuilder().setSession(session).build());
  }

  @Override
  public void stopProfiling(Common.Session session) {
    // TODO: handle different status of the response
    myProfilers.getClient().getCpuClient().stopMonitoringApp(
      CpuStopRequest.newBuilder()
        .setSession(session)
        // This is needed to stop an ongoing trace and should be handled via an explicit stop-trace command in the new pipeline.
        // TODO b/119261457 In the new pipeline, we can potentially pass the same info down via EndSession.
        .setAppName(myProfilers.getProcess() != null ? myProfilers.getProcess().getName() : "")
        .build());
  }

  /**
   * Copies the content of the trace file corresponding to a {@link TraceInfo} to a given {@link FileOutputStream}.
   */
  static void saveCaptureToFile(@NotNull StudioProfilers profilers, @NotNull CpuTraceInfo info, @NotNull OutputStream outputStream) {

    try {
      Transport.BytesRequest traceRequest = Transport.BytesRequest.newBuilder()
        .setStreamId(profilers.getSession().getStreamId())
        .setId(String.valueOf(info.getTraceId()))
        .build();
      Transport.BytesResponse traceResponse = profilers.getClient().getTransportClient().getBytes(traceRequest);

      // Atrace Format = [HEADER|ZlibData][HEADER|ZlibData]
      // Systrace Expected format = [HEADER|ZlipData]
      // As such exporting the file raw Systrace will only read the first header/data chunk.
      // Atrace captures come over as several parts combined into one file. As such we need an exporter
      // to handle converting the format to a format that Systrace can support. The reason for the multi-part file
      // is because Atrace dumps a compressed data file every X interval and this file represents the concatenation of all
      // the individual dumps.
      if (info.getConfiguration().getUserOptions().getTraceType() == CpuTraceType.ATRACE) {
        File trace = FileUtil.createTempFile(String.format("cpu_trace_%d", info.getTraceId()), ".trace", true);
        try (FileOutputStream out = new FileOutputStream(trace)) {
          out.write(traceResponse.getContents().toByteArray());
        }
        AtraceExporter.export(trace, outputStream);
      }
      else {
        FileUtil.copy(new ByteArrayInputStream(traceResponse.getContents().toByteArray()), outputStream);
      }
    }
    catch (IOException exception) {
      getLogger().warn("Failed to export CPU trace file:\n" + exception);
    }
  }

  /**
   * Generate a default name for a trace to be exported. The name suggested is based on the current timestamp and the capture type.
   */
  @NotNull
  static String generateCaptureFileName(@NotNull CpuTraceType profilerType) {
    StringBuilder traceName = new StringBuilder(String.format("cpu-%s-", StringUtil.toLowerCase(profilerType.name())));
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    traceName.append(LocalDateTime.now().format(formatter));
    return traceName.toString();
  }

  /**
   * Returns the list of {@link CpuTraceInfo} that intersect with the given range.
   *
   * @param rangeUs note that this is a microsecond range. If the range min/max value is Long.MINVALUE/Long.MAXVALUE
   *                respectively, the MIN/MAX values will be used for the query. Otherwise conversion to nanoseconds result
   *                in overflows.
   */
  @NotNull
  public static List<CpuTraceInfo> getTraceInfoFromRange(@NotNull ProfilerClient client,
                                                         @NotNull Common.Session session,
                                                         @NotNull Range rangeUs,
                                                         boolean newPipeline) {
    // Converts the range to nanoseconds before calling the service.
    long rangeMinNs = rangeUs.getMin() == Long.MIN_VALUE ? Long.MIN_VALUE : TimeUnit.MICROSECONDS.toNanos((long)rangeUs.getMin());
    long rangeMaxNs = rangeUs.getMax() == Long.MAX_VALUE ? Long.MAX_VALUE : TimeUnit.MICROSECONDS.toNanos((long)rangeUs.getMax());

    List<CpuTraceInfo> traceInfoList = new ArrayList<>();
    if (newPipeline) {
      Transport.GetEventGroupsResponse response = client.getTransportClient().getEventGroups(
        Transport.GetEventGroupsRequest.newBuilder()
          .setStreamId(session.getStreamId())
          .setPid(session.getPid())
          .setKind(Common.Event.Kind.CPU_TRACE)
          .setFromTimestamp(rangeMinNs)
          .setToTimestamp(rangeMaxNs)
          .build());
      traceInfoList = response.getGroupsList().stream()
        .map(group -> {
          // We only care about the CpuTraceInfo stored in the very last event in the group.
          Common.Event event = group.getEvents(group.getEventsCount() - 1);
          Cpu.CpuTraceData traceData = event.getCpuTrace();
          return traceData.hasTraceStarted() ? traceData.getTraceStarted().getTraceInfo() : traceData.getTraceEnded().getTraceInfo();
        })
        .sorted(Comparator.comparingLong(CpuTraceInfo::getFromTimestamp))
        .collect(Collectors.toList());
    }
    else {
      GetTraceInfoResponse response = client.getCpuClient().getTraceInfo(GetTraceInfoRequest.newBuilder()
                                                                           .setSession(session)
                                                                           .setFromTimestamp(rangeMinNs)
                                                                           .setToTimestamp(rangeMaxNs)
                                                                           .build());
      traceInfoList.addAll(response.getTraceInfoList().stream().collect(Collectors.toList()));
    }
    return traceInfoList;
  }

  /**
   * Gets the trace info for a given trace id.
   * This function only uses the unified pipeline.
   */
  @NotNull
  public static CpuTraceInfo getTraceInfoFromId(@NotNull StudioProfilers profilers, long traceId) {
    if (!profilers.getIdeServices().getFeatureConfig().isUnifiedPipelineEnabled()) {
      return CpuTraceInfo.getDefaultInstance();
    }

    Transport.GetEventGroupsResponse response = profilers.getClient().getTransportClient().getEventGroups(
      Transport.GetEventGroupsRequest.newBuilder()
        .setStreamId(profilers.getSession().getStreamId())
        .setKind(Common.Event.Kind.CPU_TRACE)
        .setGroupId(traceId)
        .build());
    if (response.getGroupsCount() == 0) {
      return CpuTraceInfo.getDefaultInstance();
    }
    Cpu.CpuTraceData data = response.getGroups(0).getEvents(response.getGroups(0).getEventsCount() - 1).getCpuTrace();
    if (data.hasTraceStarted()) {
      return data.getTraceStarted().getTraceInfo();
    }
    return data.getTraceEnded().getTraceInfo();
  }

  /**
   * Returns the list of all {@link CpuTraceInfo} for a given session.
   */
  @NotNull
  public static List<CpuTraceInfo> getTraceInfoFromSession(@NotNull ProfilerClient client,
                                                           @NotNull Common.Session session,
                                                           boolean newPipeline) {
    return getTraceInfoFromRange(client, session, new Range(Long.MIN_VALUE, Long.MAX_VALUE), newPipeline);
  }
}
