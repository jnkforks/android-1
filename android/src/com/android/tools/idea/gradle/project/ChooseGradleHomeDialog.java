/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.gradle.project;

import static com.android.SdkConstants.GRADLE_MINIMUM_VERSION;
import static com.android.tools.idea.gradle.util.GradleUtil.GRADLE_SYSTEM_ID;
import static com.android.tools.idea.io.FilePaths.toSystemDependentPath;
import static com.intellij.openapi.externalSystem.model.settings.LocationSettingType.EXPLICIT_CORRECT;
import static com.intellij.openapi.externalSystem.model.settings.LocationSettingType.EXPLICIT_INCORRECT;
import static com.intellij.openapi.util.text.StringUtil.isNotEmpty;
import static org.jetbrains.plugins.gradle.util.GradleUtil.getGradleHomeFileChooserDescriptor;
import static org.jetbrains.plugins.gradle.util.GradleUtil.getLastUsedGradleHome;
import static org.jetbrains.plugins.gradle.util.GradleUtil.storeLastUsedGradleHome;

import com.android.ide.common.repository.GradleVersion;
import com.android.tools.idea.gradle.util.GradleVersions;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.externalSystem.model.settings.LocationSettingType;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import java.io.File;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import org.jdesktop.swingx.JXLabel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.service.GradleInstallationManager;
import org.jetbrains.plugins.gradle.util.GradleBundle;

/**
 * Dialog where users select the path of the local Gradle installation to use when importing a project.
 */
public class ChooseGradleHomeDialog extends DialogWrapper {
  @VisibleForTesting
  public static final String VALIDATION_MESSAGE_CLIENT_PROPERTY = "validation.message";

  @NotNull private final GradleInstallationManager myInstallationManager;
  @Nullable private final String myMinimumGradleVersion;

  private TextFieldWithBrowseButton myGradleHomePathField;
  private JBLabel myGradleHomeLabel;
  private JPanel myPanel;
  private JXLabel myDescriptionLabel;

  public ChooseGradleHomeDialog() {
    this(GRADLE_MINIMUM_VERSION);
  }

  public ChooseGradleHomeDialog(@Nullable String minimumGradleVersion) {
    this("Import Gradle Project", minimumGradleVersion);
  }

  public ChooseGradleHomeDialog(@NotNull @NlsContexts.DialogTitle String title, @Nullable String minimumGradleVersion) {
    super(null);
    myMinimumGradleVersion = minimumGradleVersion;
    myInstallationManager = ServiceManager.getService(GradleInstallationManager.class);
    init();
    initValidation();
    setTitle(title);

    FileChooserDescriptor fileChooserDescriptor = getGradleHomeFileChooserDescriptor();
    myGradleHomePathField.addBrowseFolderListener("", GradleBundle.message("gradle.settings.text.home.path"), null, fileChooserDescriptor,
                                                  TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT);

    myGradleHomeLabel.setLabelFor(myGradleHomePathField.getTextField());
    // This prevents the weird sizing in Linux.
    getPeer().getWindow().pack();

    myGradleHomePathField.setText(getLastUsedGradleHome());
    myGradleHomePathField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
          String fixedPath = myInstallationManager.suggestBetterGradleHomePath(myGradleHomePathField.getText());
          if (fixedPath != null) {
            ApplicationManager.getApplication().invokeLater(()-> {
              myGradleHomePathField.setText(fixedPath);
            });
          } else {
            initValidation();
          }
      }
    });
  }

  public void setDescription(@NotNull String descriptionLabel) {
    myDescriptionLabel.setText(descriptionLabel);
  }

  @Nullable
  @Override
  protected JComponent createCenterPanel() {
    return myPanel;
  }

  @Nullable
  @Override
  protected ValidationInfo doValidate() {
    LocationSettingType locationSettingType = validateLocation();
    switch (locationSettingType) {
      case EXPLICIT_CORRECT:
        ValidationInfo validationInfo = validateMinimumGradleVersion();
        if (validationInfo != null) {
          return validationInfo;
        }
        return super.doValidate();
      default:
        return newPathIsInvalidInfo(locationSettingType.getDescription(GRADLE_SYSTEM_ID));
    }
  }

  @NotNull
  private LocationSettingType validateLocation() {
    String gradleHome = getEnteredGradleHomePath();
    if (gradleHome.isEmpty()) {
      return LocationSettingType.UNKNOWN;
    }
    File gradleHomePath = getGradleHomePath(gradleHome);
    return myInstallationManager.isGradleSdkHome(gradleHomePath) ? EXPLICIT_CORRECT : EXPLICIT_INCORRECT;
  }

  @Nullable
  private ValidationInfo validateMinimumGradleVersion() {
    if (isNotEmpty(myMinimumGradleVersion)) {
      // When we reach this point we know the path entered is a valid Gradle home path. Now we need to verify the version of Gradle at that
      // location is equal or greater than the one in myMinimumGradleVersion.
      GradleVersion minimum = GradleVersion.parse(myMinimumGradleVersion);

      File enteredGradleHomePath = getGradleHomePath(getEnteredGradleHomePath());
      GradleVersion gradleVersion = GradleVersions.getInstance().getGradleVersion(enteredGradleHomePath);

      if (gradleVersion == null) {
        return newPathIsInvalidInfo("Unable to detect Gradle version");
      }

      if (minimum.compareTo(gradleVersion) > 0) {
        return newPathIsInvalidInfo(String.format("Gradle %1$s or newer is required", myMinimumGradleVersion));
      }
    }
    return null;
  }

  @NotNull
  private ValidationInfo newPathIsInvalidInfo(@NotNull String msg) {
    storeErrorMessage(msg);
    return new ValidationInfo(msg, myGradleHomePathField.getTextField());
  }

  private void storeErrorMessage(@NotNull String msg) {
    myGradleHomePathField.putClientProperty(VALIDATION_MESSAGE_CLIENT_PROPERTY, msg);
  }

  @NotNull
  private static File getGradleHomePath(@NotNull String gradleHome) {
    return toSystemDependentPath(gradleHome);
  }

  public void storeLastUsedGradleHomePath() {
    storeLastUsedGradleHome(getEnteredGradleHomePath());
  }

  @NotNull
  public String getEnteredGradleHomePath() {
    return myGradleHomePathField.getText();
  }
}
