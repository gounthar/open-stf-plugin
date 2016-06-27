package hudson.plugins.openstf;

import static hudson.plugins.android_emulator.AndroidEmulator.log;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Result;
import hudson.plugins.android_emulator.AndroidEmulator;
import hudson.plugins.android_emulator.SdkInstallationException;
import hudson.plugins.android_emulator.SdkInstaller;
import hudson.plugins.android_emulator.sdk.AndroidSdk;
import hudson.plugins.android_emulator.sdk.Tool;
import hudson.plugins.openstf.exception.ApiFailedException;
import hudson.plugins.openstf.util.Utils;
import hudson.remoting.Callable;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ArgumentListBuilder;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.NullStream;
import io.swagger.client.model.DeviceListResponseDevices;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class STFBuildWrapper extends BuildWrapper {

  /** Timeout value for STF device connection to complete. */
  private static final int STF_DEVICE_CONNECT_COMPLETE_TIMEOUT_MS = 30 * 1000;

  /** Interval during which killing a process should complete. */
  private static final int KILL_PROCESS_TIMEOUT_MS = 10 * 1000;

  private DescriptorImpl descriptor;
  private AndroidEmulator.DescriptorImpl emulatorDescriptor;

  public JSONObject deviceCondition;
  public String model;
  public String version;
  public final int deviceReleaseWaitTime;

  /**
   * Allocates a STFBuildWrapper object.
   * @param deviceCondition Condition set of the STF device user want to use.
   * @param deviceReleaseWaitTime Waiting-time for the STF device to be released
   */
  @DataBoundConstructor
  public STFBuildWrapper(JSONObject deviceCondition, int deviceReleaseWaitTime) {
    this.deviceCondition = deviceCondition;
    this.model = deviceCondition.getString("model");
    this.version = deviceCondition.getString("version");
    this.deviceReleaseWaitTime = deviceReleaseWaitTime;
  }

  @Override
  public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener)
      throws IOException, InterruptedException {

    final PrintStream logger = listener.getLogger();

    Hudson hudsonInstance = Hudson.getInstance();
    if (hudsonInstance == null) {
      log(logger, Messages.CANNOT_GET_HUDSON_INSTANCE());
      build.setResult(Result.FAILURE);
      return null;
    }

    if (descriptor == null) {
      descriptor = hudsonInstance.getDescriptorByType(DescriptorImpl.class);
    }
    if (emulatorDescriptor == null) {
      emulatorDescriptor = hudsonInstance.getDescriptorByType(AndroidEmulator.DescriptorImpl.class);
    }

    // Substitute environment and build variables into config
    final EnvVars envVars = hudson.plugins.android_emulator.util.Utils
        .getEnvironment(build, listener);
    final Map<String, String> buildVars = build.getBuildVariables();

    String stfApiEndpoint = descriptor.stfApiEndpoint;
    String stfToken = descriptor.stfToken;
    Boolean useSpecificKey = descriptor.useSpecificKey;
    String adbPublicKey = descriptor.adbPublicKey;
    String adbPrivateKey = descriptor.adbPrivateKey;
    JSONObject deviceFilter = Utils.expandVariables(envVars, buildVars, this.deviceCondition);

    Utils.setupSTFApiClient(stfApiEndpoint, stfToken);

    // SDK location
    Node node = Computer.currentComputer().getNode();
    String androidHome = hudson.plugins.android_emulator.util.Utils
        .expandVariables(envVars, buildVars, emulatorDescriptor.androidHome);
    androidHome = hudson.plugins.android_emulator.util.Utils
        .discoverAndroidHome(launcher, node, envVars, androidHome);

    // Confirm that the required SDK tools are available
    AndroidSdk androidSdk = hudson.plugins.android_emulator.util.Utils
        .getAndroidSdk(launcher, androidHome, null);
    if (androidSdk == null) {
      if (!emulatorDescriptor.shouldInstallSdk) {
        // Couldn't find an SDK, don't want to install it, give up
        log(logger, hudson.plugins.android_emulator.Messages.SDK_TOOLS_NOT_FOUND());
        build.setResult(Result.NOT_BUILT);
        return null;
      }

      // Ok, let's download and install the SDK
      log(logger, hudson.plugins.android_emulator.Messages.INSTALLING_SDK());
      try {
        androidSdk = SdkInstaller.install(launcher, listener, null);
      } catch (SdkInstallationException ex) {
        log(logger, hudson.plugins.android_emulator.Messages.SDK_INSTALLATION_FAILED(), ex);
        build.setResult(Result.NOT_BUILT);
        return null;
      }
    }

    String displayHome =
        androidSdk.hasKnownRoot()
            ? androidSdk.getSdkRoot() : hudson.plugins.android_emulator.Messages.USING_PATH();
    log(logger, hudson.plugins.android_emulator.Messages.USING_SDK(displayHome));

    STFConfig stfConfig = new STFConfig(useSpecificKey, adbPublicKey, adbPrivateKey,
        deviceFilter, deviceReleaseWaitTime);

    return doSetup(build, launcher, listener, androidSdk, stfConfig);
  }

  private Environment doSetup(final AbstractBuild<?, ?> build, final Launcher launcher,
        final BuildListener listener, final AndroidSdk androidSdk, final STFConfig stfConfig)
        throws IOException, InterruptedException {

    final PrintStream logger = listener.getLogger();

    final AndroidRemoteContext remote =
        new AndroidRemoteContext(build, launcher, listener, androidSdk);

    try {
      String reservedDeviceId = stfConfig.reserve();
      DeviceListResponseDevices device = Utils.getSTFDeviceById(reservedDeviceId);
      remote.setDevice(device);
    } catch (STFException ex) {
      log(logger, ex.getMessage());
      build.setResult(Result.NOT_BUILT);
      if (remote.getDevice() != null) {
        cleanUp(stfConfig, remote);
      }
      return null;
    }

    if (stfConfig.getUseSpecificKey()) {
      try {
        Callable<Boolean, IOException> task = stfConfig.getAdbKeySettingTask(listener);
        launcher.getChannel().call(task);
      } catch (IOException ex) {
        log(logger, Messages.CANNOT_CREATE_ADBKEY_FILE());
        build.setResult(Result.NOT_BUILT);
        cleanUp(stfConfig, remote);
        return null;
      }
    }

    // We manually start the adb-server so that later commands will not have to start it,
    // allowing them to complete faster.
    Proc adbStart =
        remote.getToolProcStarter(Tool.ADB, "start-server").stdout(logger).stderr(logger).start();
    adbStart.joinWithTimeout(5L, TimeUnit.SECONDS, listener);
    Proc adbStart2 =
        remote.getToolProcStarter(Tool.ADB, "start-server").stdout(logger).stderr(logger).start();
    adbStart2.joinWithTimeout(5L, TimeUnit.SECONDS, listener);

    // Start dumping logcat to temporary file
    final File artifactsDir = build.getArtifactsDir();
    final FilePath workspace = build.getWorkspace();
    if (workspace == null) {
      log(logger, Messages.CANNOT_GET_WORKSPACE_ON_THIS_BUILD());
      build.setResult(Result.FAILURE);
      cleanUp(stfConfig, remote);
      return null;
    }
    final FilePath logcatFile = workspace.createTextTempFile("logcat_", ".log", "", false);
    final OutputStream logcatStream = logcatFile.write();
    final String logcatArgs = String.format("-s %s logcat -v time", remote.serial());
    final Proc logWriter = remote.getToolProcStarter(Tool.ADB, logcatArgs)
        .stdout(logcatStream).stderr(new NullStream()).start();

    // Make sure we're still connected
    connect(remote);

    log(logger, Messages.WAITING_FOR_STF_DEVICE_CONNECT_COMPLETION());
    int connectTimeout = STF_DEVICE_CONNECT_COMPLETE_TIMEOUT_MS;
    boolean connectSucceeded = waitForSTFDeviceConnectCompletion(connectTimeout, remote);

    if (!connectSucceeded) {
      log(logger, Messages.CONNECTING_STF_DEVICE_FAILED());
      build.setResult(Result.FAILURE);
      cleanUp(stfConfig, remote);
      return null;
    }

    // Wait for Authentication
    Thread.sleep(5 * 1000);

    return new Environment() {
      @Override
      public void buildEnvVars(Map<String, String> env) {
        env.put("ANDROID_SERIAL", remote.serial());
        env.put("ANDROID_AVD_DEVICE", remote.serial());
        env.put("ANDROID_ADB_SERVER_PORT", Integer.toString(remote.adbServerPort()));
        env.put("ANDROID_TMP_LOGCAT_FILE", logcatFile.getRemote());
        if (androidSdk.hasKnownRoot()) {
          env.put("JENKINS_ANDROID_HOME", androidSdk.getSdkRoot());
          env.put("ANDROID_HOME", androidSdk.getSdkRoot());

          // Prepend the commonly-used Android tools to the start of the PATH for this build
          env.put("PATH+SDK_TOOLS", androidSdk.getSdkRoot() + "/tools/");
          env.put("PATH+SDK_PLATFORM_TOOLS", androidSdk.getSdkRoot() + "/platform-tools/");
          // TODO: Export the newest build-tools folder as well, so aapt and friends can be used
        }
      }

      @Override
      public boolean tearDown(AbstractBuild build, BuildListener listener)
          throws IOException, InterruptedException {

        cleanUp(stfConfig, remote, logWriter, logcatFile, logcatStream, artifactsDir);
        return true;
      }
    };
  }

  private static void connect(AndroidRemoteContext remote)
      throws IOException, InterruptedException {

    ArgumentListBuilder adbConnectCmd = remote
        .getToolCommand(Tool.ADB, "connect " + remote.serial());
    remote.getProcStarter(adbConnectCmd).start()
        .joinWithTimeout(5L, TimeUnit.SECONDS, remote.launcher().getListener());
  }

  private static void disconnect(AndroidRemoteContext remote)
      throws IOException, InterruptedException {
    final String args = "disconnect " + remote.serial();
    ArgumentListBuilder adbDisconnectCmd = remote.getToolCommand(Tool.ADB, args);
    remote.getProcStarter(adbDisconnectCmd).start()
        .joinWithTimeout(5L, TimeUnit.SECONDS, remote.launcher().getListener());
  }

  private void cleanUp(STFConfig stfConfig, AndroidRemoteContext remote)
    throws IOException, InterruptedException {
    cleanUp(stfConfig, remote, null, null, null, null);
  }

  private void cleanUp(STFConfig stfConfig, AndroidRemoteContext remote, Proc logcatProcess,
      FilePath logcatFile, OutputStream logcatStream, File artifactsDir)
      throws IOException, InterruptedException {

    // Disconnect STF device from adb
    disconnect(remote);

    try {
      stfConfig.release(remote.getDevice());
    } catch (STFException ex) {
      log(remote.logger(), ex.getMessage());
    }

    // Clean up logging process
    if (logcatProcess != null) {
      if (logcatProcess.isAlive()) {
        // This should have stopped when the emulator was,
        // but if not attempt to kill the process manually.
        // First, give it a final chance to finish cleanly.
        Thread.sleep(3 * 1000);
        if (logcatProcess.isAlive()) {
          hudson.plugins.android_emulator.util.Utils
              .killProcess(logcatProcess, KILL_PROCESS_TIMEOUT_MS);
        }
      }
      try {
        logcatStream.close();
      } catch (Exception ex) {
        // ignore
      }

      // Archive the logs
      if (logcatFile.length() != 0) {
        log(remote.logger(), hudson.plugins.android_emulator.Messages.ARCHIVING_LOG());
        logcatFile.copyTo(new FilePath(artifactsDir).child("logcat.txt"));
      }
      logcatFile.delete();
    }

    ArgumentListBuilder adbKillCmd = remote.getToolCommand(Tool.ADB, "kill-server");
    remote.getProcStarter(adbKillCmd).join();

    remote.cleanUp();
  }

  private boolean waitForSTFDeviceConnectCompletion(final int timeout,
      AndroidRemoteContext remote) {

    long start = System.currentTimeMillis();
    int sleep = timeout / (int) (Math.sqrt(timeout / (double) 1000) * 2);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ArgumentListBuilder adbDevicesCmd = remote.getToolCommand(Tool.ADB, "devices");

    try {
      while (System.currentTimeMillis() < start + timeout) {
        remote.getProcStarter(adbDevicesCmd).stdout(out).stderr(out).start()
            .joinWithTimeout(5L, TimeUnit.SECONDS, remote.launcher().getListener());

        String devicesResult = out.toString(Utils.getDefaultCharset().displayName());
        String lineSeparator =
            Computer.currentComputer().getSystemProperties().get("line.separator").toString();
        for (String line: devicesResult.split(lineSeparator)) {
          if (line.contains(remote.serial()) && line.contains("device")) {
            return true;
          }
        }

        Thread.sleep(sleep);
      }
    } catch (InterruptedException ex) {
      log(remote.logger(), Messages.INTERRUPTED_DURING_STF_DEVICE_CONNECT_COMPLETION());
    } catch (IOException ex) {
      log(remote.logger(), Messages.COULD_NOT_CHECK_STF_DEVICE_CONNECT_COMPLETION());
      ex.printStackTrace(remote.logger());
    }
    return false;
  }

  @Extension
  public static final class DescriptorImpl extends BuildWrapperDescriptor {

    public String stfApiEndpoint = "";
    public String stfToken = "";
    public Boolean useSpecificKey = false;
    public String adbPublicKey;
    public String adbPrivateKey;

    public DescriptorImpl() {
      super(STFBuildWrapper.class);
      load();
    }

    @Override
    public String getDisplayName() {
      return Messages.JOB_DESCRIPTION();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
      stfApiEndpoint = json.optString("stfApiEndpoint");
      stfToken = json.optString("stfToken");
      useSpecificKey = json.optBoolean("useSpecificKey", false);
      if (useSpecificKey) {
        adbPublicKey = Util.fixEmptyAndTrim(json.optString("adbPublicKey"));
        adbPrivateKey = Util.fixEmptyAndTrim(json.optString("adbPrivateKey"));
      } else {
        adbPublicKey = null;
        adbPrivateKey = null;
      }
      save();
      return true;
    }

    @Override
    public BuildWrapper newInstance(StaplerRequest req, JSONObject formData) throws FormException {
      int deviceReleaseWaitTime = 0;

      try {
        deviceReleaseWaitTime = Integer.parseInt(formData.getString("deviceReleaseWaitTime"));
        if (deviceReleaseWaitTime < 0) {
          deviceReleaseWaitTime = 0;
        }
      } catch (NumberFormatException ex) {
        // ignore
      } finally {
        formData.discard("deviceReleaseWaitTime");
      }

      JSONObject deviceCondition = JSONObject.fromObject(formData);

      return new STFBuildWrapper(deviceCondition, deviceReleaseWaitTime);
    }

    @Override
    public boolean isApplicable(AbstractProject<?, ?> item) {
      return true;
    }

    /**
     * Setting device model values on jelly.
     * This Method called by Jenkins.
     * @return List of Device Model values.
     */
    public ComboBoxModel doFillModelItems() {
      Utils.setupSTFApiClient(stfApiEndpoint, stfToken);
      return Utils.getSTFDeviceAttributeComboBoxItems("model");
    }

    /**
     * Setting OS version values on jelly.
     * This Method called by Jenkins.
     * @return List of OS version values.
     */
    public ComboBoxModel doFillVersionItems() {
      Utils.setupSTFApiClient(stfApiEndpoint, stfToken);
      return Utils.getSTFDeviceAttributeComboBoxItems("version");
    }

    /**
     * Checking whether the given os version is valid.
     * This Method called by Jenkins.
     * @return validation result.
     */
    public FormValidation doCheckOsVersion(@QueryParameter String value) {
      if (value == null || value.equals("")) {
        return FormValidation.ok();
      }
      if (value.matches(Constants.REGEX_VARIABLE) || value.matches(Constants.REGEX_REGEX)
          || value.equals("any")) {
        return FormValidation.ok();
      }
      if (!value.matches(Constants.REGEX_OS_VERSION)) {
        return FormValidation.error(Messages.INVALID_OS_VERSION());
      }
      return FormValidation.ok();
    }

    public FormValidation doCheckSTFApiEndpoint(@QueryParameter String value) {
      return Utils.validateSTFApiEndpoint(value);
    }

    public FormValidation doCheckSTFToken(@QueryParameter String stfApiEndpoint,
        @QueryParameter String stfToken) {
      return Utils.validateSTFToken(stfApiEndpoint, stfToken);
    }

    /**
     * Display a warning message if 'useSpecificKey' option is selected.
     * This Method called by Jenkins.
     * @return validation result.
     */
    public FormValidation doCheckUseSpecificKey(@QueryParameter Boolean value) {
      if (value) {
        return FormValidation.warning(Messages.ADBKEY_FILE_WILL_BE_OVERWRITTEN());
      } else {
        return FormValidation.ok();
      }
    }

    /**
     * Gets a list of devices that match the given filter, as JSON Array.
     * This method called by javascript in jelly.
     * @param filter Conditions of the STF device you want to get.
     * @return List of STF devices that meet the filter.
     */
    @JavaScriptMethod
    public JSONArray getDeviceListJSON(JSONObject filter) {

      Utils.setupSTFApiClient(stfApiEndpoint, stfToken);

      try {
        List<DeviceListResponseDevices> deviceList = Utils.getDeviceList(filter);
        return JSONArray.fromObject(deviceList);
      } catch (ApiFailedException ex) {
        return new JSONArray();
      }
    }

    @JavaScriptMethod
    public synchronized String getStfApiEndpoint() {
      return String.valueOf(stfApiEndpoint);
    }
  }
}
