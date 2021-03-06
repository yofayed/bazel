// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import static com.google.common.collect.MoreCollectors.onlyElement;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction.Substitution;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction.Template;
import com.google.devtools.build.lib.analysis.test.ExecutionInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.syntax.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An implementation for the "android_device" rule.
 */
public class AndroidDevice implements RuleConfiguredTargetFactory {

  private static final Template STUB_SCRIPT =
      Template.forResource(AndroidDevice.class, "android_device_stub_template.txt");

  private static final String DEVICE_BROKER_TYPE = "WRAPPED_EMULATOR";

  // Min resolution
  private static final int MIN_HORIZONTAL = 240;
  private static final int MIN_VERTICAL = 240;

  private static final int MIN_RAM = 64;
  private static final int MAX_RAM = 4096;
  private static final int MIN_VM_HEAP = 16;
  private static final int MIN_CACHE = 16;

  // http://en.wikipedia.org/wiki/List_of_displays_by_pixel_density
  // this is a much lower pixels-per-inch then even some of the oldest phones.
  private static final int MIN_LCD_DENSITY = 30;

  private static final Predicate<Artifact> SOURCE_PROPERTIES_SELECTOR =
      (Artifact artifact) -> "source.properties".equals(artifact.getPath().getBaseName());

  private static final Predicate<Artifact> SOURCE_PROPERTIES_FILTER = Predicates.not(
      SOURCE_PROPERTIES_SELECTOR);
  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException {
    Artifact executable = ruleContext.createOutputArtifact();
    Artifact metadata = ruleContext.getImplicitOutputArtifact(
        AndroidRuleClasses.ANDROID_DEVICE_EMULATOR_METADATA);
    Artifact images = ruleContext.getImplicitOutputArtifact(
        AndroidRuleClasses.ANDROID_DEVICE_USERDATA_IMAGES);

    NestedSetBuilder<Artifact> filesBuilder = NestedSetBuilder.stableOrder();
    filesBuilder.add(executable);
    filesBuilder.add(metadata);
    filesBuilder.add(images);
    NestedSet<Artifact> filesToBuild = filesBuilder.build();

    Map<String, String> executionInfo = TargetUtils.getExecutionInfo(ruleContext.getRule());
    AndroidDeviceRuleAttributes deviceAttributes = new AndroidDeviceRuleAttributes(
        ruleContext, ImmutableMap.copyOf(executionInfo));
    if (ruleContext.hasErrors()) {
      return null;
    }

    // dependencies needed for the runfiles collector.
    Iterable<Artifact> commonDependencyArtifacts = deviceAttributes.getCommonDependencies();

    deviceAttributes.createStubScriptAction(metadata, images, executable, ruleContext);
    deviceAttributes.createBootAction(metadata, images);

    Runfiles runfiles = new Runfiles.Builder(ruleContext.getWorkspaceName())
        .addTransitiveArtifacts(filesToBuild)
        .addArtifacts(commonDependencyArtifacts)
        .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
        .merge(
            ruleContext
                .getExecutablePrerequisite("$unified_launcher", Mode.HOST)
                .getRunfilesSupport())
        .build();
    RunfilesSupport runfilesSupport =
        RunfilesSupport.withExecutable(ruleContext, runfiles, executable);
    return new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(filesToBuild)
        .addProvider(RunfilesProvider.class, RunfilesProvider.simple(runfiles))
        .setRunfilesSupport(runfilesSupport, executable)
        .addNativeDeclaredProvider(new ExecutionInfo(executionInfo))
        .addProvider(
            DeviceBrokerTypeProvider.class, new DeviceBrokerTypeProvider(DEVICE_BROKER_TYPE))
        .build();
  }

  /**
   * Handles initialization phase validation of attributes, collection of dependencies and creation
   * of actions.
   */
  private static class AndroidDeviceRuleAttributes {
    private final RuleContext ruleContext;
    private final ImmutableMap<String, String> constraints;

    private final Artifact adb;
    private final Artifact emulatorArm;
    private final Artifact emulatorX86;
    private final Artifact adbStatic;
    private final ImmutableList<Artifact> emulatorX86Bios;
    private final ImmutableList<Artifact> xvfbSupportFiles;
    private final Artifact mksdcard;
    private final Artifact snapshotFs;
    private final FilesToRunProvider unifiedLauncher;
    private final Artifact androidRuntest;
    private final ImmutableList<Artifact> androidRuntestDeps;
    private final Artifact testingShbase;
    private final ImmutableList<Artifact> testingShbaseDeps;
    private final Optional<Artifact> defaultProperties;
    private final Iterable<Artifact> platformApks;
    private final Artifact sdkPath;

    private final int ram;
    private final int cache;
    private final int vmHeap;
    private final int density;
    private final int horizontalResolution;
    private final int verticalResolution;

    // These can only be null if there was an error during creation.
    private Iterable<Artifact> systemImages;
    private Artifact sourcePropertiesFile;
    private ImmutableList<Artifact> commonDependencies;

    private AndroidDeviceRuleAttributes(
        RuleContext ruleContext, ImmutableMap<String, String> executionInfo) {
      this.ruleContext = ruleContext;
      this.constraints = executionInfo;
      horizontalResolution = ruleContext.attributes().get("horizontal_resolution", Type.INTEGER);
      verticalResolution = ruleContext.attributes().get("vertical_resolution", Type.INTEGER);
      ram = ruleContext.attributes().get("ram", Type.INTEGER);
      density = ruleContext.attributes().get("screen_density", Type.INTEGER);
      cache = ruleContext.attributes().get("cache", Type.INTEGER);
      vmHeap = ruleContext.attributes().get("vm_heap", Type.INTEGER);

      defaultProperties = Optional.fromNullable(
          ruleContext.getPrerequisiteArtifact("default_properties", Mode.HOST));
      adb = ruleContext.getPrerequisiteArtifact("$adb", Mode.HOST);
      emulatorArm = ruleContext.getPrerequisiteArtifact("$emulator_arm", Mode.HOST);
      emulatorX86 = ruleContext.getPrerequisiteArtifact("$emulator_x86", Mode.HOST);
      adbStatic = ruleContext.getPrerequisiteArtifact("$adb_static", Mode.HOST);
      emulatorX86Bios =
          ruleContext.getPrerequisiteArtifacts("$emulator_x86_bios", Mode.HOST).list();
      xvfbSupportFiles = ruleContext.getPrerequisiteArtifacts("$xvfb_support", Mode.HOST).list();
      mksdcard = ruleContext.getPrerequisiteArtifact("$mksd", Mode.HOST);
      snapshotFs = ruleContext.getPrerequisiteArtifact("$empty_snapshot_fs", Mode.HOST);
      unifiedLauncher = ruleContext.getExecutablePrerequisite("$unified_launcher", Mode.HOST);
      androidRuntestDeps =
          ruleContext.getPrerequisiteArtifacts("$android_runtest", Mode.HOST).list();
      androidRuntest =
          androidRuntestDeps.stream().filter(Artifact::isSourceArtifact).collect(onlyElement());
      testingShbaseDeps = ruleContext.getPrerequisiteArtifacts("$testing_shbase", Mode.HOST).list();
      testingShbase =
          testingShbaseDeps
              .stream()
              .filter(
                  (Artifact artifact) -> "googletest.sh".equals(artifact.getPath().getBaseName()))
              .collect(onlyElement());

      // may be empty
      platformApks = ruleContext.getPrerequisiteArtifacts("platform_apks", Mode.TARGET).list();
      sdkPath = ruleContext.getPrerequisiteArtifact("$sdk_path", Mode.HOST);

      TransitiveInfoCollection systemImagesAndSourceProperties =
          ruleContext.getPrerequisite("system_image", Mode.TARGET);
      if (ruleContext.hasErrors()) {
        return;
      }

      Iterable<Artifact> files =
          systemImagesAndSourceProperties.getProvider(FileProvider.class).getFilesToBuild();
      sourcePropertiesFile = Iterables.tryFind(files, SOURCE_PROPERTIES_SELECTOR).orNull();
      systemImages = Iterables.filter(files, SOURCE_PROPERTIES_FILTER);
      validateAttributes();
      if (sourcePropertiesFile == null) {
        ruleContext.attributeError("system_image", "No source.properties files exist in this "
            + "filegroup (" + systemImagesAndSourceProperties.getLabel() + ")");
      }
      int numberOfSourceProperties = Iterables.size(files) - Iterables.size(systemImages);
      if (numberOfSourceProperties > 1) {
        ruleContext.attributeError("system_image", "Multiple source.properties files exist in "
            + "this filegroup (" + systemImagesAndSourceProperties.getLabel() + ")");
      }
      if (ruleContext.hasErrors()) {
        return;
      }

      commonDependencies = ImmutableList.<Artifact>builder()
          .add(adb)
          .add(sourcePropertiesFile)
          .addAll(systemImages)
          .add(emulatorArm)
          .add(emulatorX86)
          .add(adbStatic)
          .addAll(emulatorX86Bios)
          .addAll(xvfbSupportFiles)
          .add(mksdcard)
          .add(snapshotFs)
          .addAll(unifiedLauncher.getFilesToRun())
          .addAll(androidRuntestDeps)
          .addAll(testingShbaseDeps)
          .addAll(platformApks)
          .build();
    }

    /*
     * The stub script will find the workspace directory of its runfiles tree and then execute
     * from there.
     * The stub script gets run via blaze run, blaze-bin or as a part of a test.
     */
    private void createStubScriptAction(
        Artifact metadata, Artifact images, Artifact executable, RuleContext ruleContext) {
      List<Substitution> arguments = new ArrayList<>();
      arguments.add(Substitution.of("%workspace%", ruleContext.getWorkspaceName()));
      arguments.add(Substitution.of("%unified_launcher%",
          unifiedLauncher.getExecutable().getRunfilesPathString()));
      arguments.add(Substitution.of("%adb%", adb.getRunfilesPathString()));
      arguments.add(Substitution.of("%adb_static%", adbStatic.getRunfilesPathString()));
      arguments.add(Substitution.of("%emulator_x86%", emulatorX86.getRunfilesPathString()));
      arguments.add(Substitution.of("%emulator_arm%", emulatorArm.getRunfilesPathString()));
      arguments.add(Substitution.of("%mksdcard%", mksdcard.getRunfilesPathString()));
      arguments.add(Substitution.of("%empty_snapshot_fs%", snapshotFs.getRunfilesPathString()));
      arguments.add(
          Substitution.of(
              "%system_images%",
              Streams.stream(systemImages)
                  .map(Artifact::getRunfilesPathString)
                  .collect(joining(" "))));
      arguments.add(
          Substitution.of(
              "%bios_files%",
              emulatorX86Bios.stream().map(Artifact::getRunfilesPathString).collect(joining(" "))));
      arguments.add(Substitution.of("%source_properties_file%",
          sourcePropertiesFile.getRunfilesPathString()));
      arguments.add(Substitution.of("%image_input_file%", images.getRunfilesPathString()));
      arguments.add(Substitution.of("%emulator_metadata_path%", metadata.getRunfilesPathString()));
      arguments.add(Substitution.of("%android_runtest%", androidRuntest.getRunfilesPathString()));
      arguments.add(Substitution.of("%testing_shbase%", testingShbase.getRunfilesPathString()));
      arguments.add(Substitution.of("%sdk_path%", sdkPath.getRunfilesPathString()));

      ruleContext.registerAction(new TemplateExpansionAction(
          ruleContext.getActionOwner(), executable, STUB_SCRIPT, arguments, true));
    }

    public void createBootAction(Artifact metadata, Artifact images) {
      // the boot action will run during the build so use execpath
      // strings to find all dependent artifacts (there is no nicely created runfiles
      // folder we're executing in).

      SpawnAction.Builder spawnBuilder = new SpawnAction.Builder()
          .addOutput(metadata)
          .addOutput(images)
          .addInputs(commonDependencies)
          .setMnemonic("AndroidDeviceBoot")
          .setProgressMessage("creating android images...")
          .setExecutionInfo(constraints)
          .setExecutable(unifiedLauncher)
          // Boot resource estimation:
          // CPU: 100% - the emulator will peg a single cpu during boot because it's a very
          //   computation intensive part of the lifecycle.
          // RAM: the emulator will use as much ram as has been requested in the device rule
          //   (there is a slight overhead for qemu's internals, but this is miniscule).
          // IO: 15% Process is IO light until the very end when the booted files are flushed to
          //   disk.
          .setResources(ResourceSet.createWithRamCpuIo(ram, 1, .0))
          .addExecutableArguments(
              "--action=boot",
              "--density=" + density,
              "--memory=" + ram,
              "--cache=" + cache,
              "--vm_size=" + vmHeap,
              "--generate_output_dir=" + images.getExecPath().getParentDirectory().getPathString(),
              "--skin=" + getScreenSize(),
              "--source_properties_file=" + sourcePropertiesFile.getExecPathString(),
              "--system_images=" + Artifact.joinExecPaths(" ", systemImages),
              "--flag_configured_android_tools",
              "--adb=" + adb.getExecPathString(),
              "--emulator_x86=" + emulatorX86.getExecPathString(),
              "--emulator_arm=" + emulatorArm.getExecPathString(),
              "--adb_static=" + adbStatic.getExecPathString(),
              "--mksdcard=" + mksdcard.getExecPathString(),
              "--empty_snapshot_fs=" + snapshotFs.getExecPathString(),
              "--bios_files=" + Artifact.joinExecPaths(",", emulatorX86Bios),
              "--nocopy_system_images",
              "--single_image_file",
              "--android_sdk_path=" + sdkPath.getExecPathString(),
              "--platform_apks=" + Artifact.joinExecPaths(",", platformApks));

      if (defaultProperties.isPresent()) {
        spawnBuilder.addInput(defaultProperties.get());
        spawnBuilder.addArgument(
          "--default_properties_file=" + defaultProperties.get().getExecPathString());
      }
      ruleContext.registerAction(spawnBuilder.build(ruleContext));
    }

    public ImmutableList<Artifact> getCommonDependencies() {
      return commonDependencies;
    }

    private void validateAttributes() {
      if (horizontalResolution < MIN_HORIZONTAL) {
        ruleContext.attributeError(
            "horizontal_resolution", "horizontal must be at least: " + MIN_HORIZONTAL);
      }
      if (verticalResolution < MIN_VERTICAL) {
        ruleContext.attributeError(
            "vertical_resolution", "vertical must be at least: " + MIN_VERTICAL);
      }
      if (ram < MIN_RAM) {
        ruleContext.attributeError("ram", "ram must be at least: " + MIN_RAM);
      }
      if (ram > MAX_RAM) {
        ruleContext.attributeError("ram", "ram cannot be greater than: " + MAX_RAM);
      }
      if (density < MIN_LCD_DENSITY) {
        ruleContext.attributeError(
            "screen_density", "density must be at least: " + MIN_LCD_DENSITY);
      }
      if (cache < MIN_CACHE) {
        ruleContext.attributeError("cache", "cache must be at least: " + MIN_CACHE);
      }
      if (vmHeap < MIN_VM_HEAP) {
        ruleContext.attributeError("vm_heap", "vm heap must be at least: " + MIN_VM_HEAP);
      }
    }

    private String getScreenSize() {
      return horizontalResolution + "x" + verticalResolution;
    }
  }
}
