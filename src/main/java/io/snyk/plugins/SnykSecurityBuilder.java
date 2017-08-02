package io.snyk.plugins;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.*;
import hudson.model.*;
import hudson.tasks.ArtifactArchiver;
import hudson.util.ArgumentListBuilder;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.*;

public class SnykSecurityBuilder extends Builder {

    private final String onFailBuild;
    private boolean isMonitor;
    private String targetFile = "";
    private String organization = "";
    private String envFlags="";

    @DataBoundConstructor
    public SnykSecurityBuilder(String onFailBuild, boolean isMonitor, String targetFile, String organization, String envFlags) {
        this.onFailBuild = onFailBuild;
        this.isMonitor = isMonitor;
        this.targetFile = targetFile;
        this.organization = organization;
        this.envFlags = envFlags;
    }

    public String isOnFailBuild(String state) {
        if (this.onFailBuild == null) {
            return "true".equals(state) ? "true" : "false";
        } else {
            return this.onFailBuild.equals(state) ? "true" : "false";
        }
    }

    public String getOnFailBuild() {
        return this.onFailBuild;
    }

    @DataBoundSetter
    public void setIsMonitor(boolean isMonitor) {
        this.isMonitor = isMonitor;
    }

    public boolean getIsMonitor() {
        return this.isMonitor;
    }

    @DataBoundSetter
    public void setTargetFile(String targetFile) {
        this.targetFile = targetFile;
    }

    public String getTargetFile() {
        return this.targetFile;
    }


    @DataBoundSetter
    public void setOrganization(String organization) {
        this.organization = organization;
    }

    public String getEnvFlags() {
        return this.envFlags;
    }

    @DataBoundSetter
    public void setEnvFlags(String envFlags) {
        this.envFlags = envFlags;
    }

    public String getOrganization() {
        return this.organization;
    }


    @Override
    @SuppressFBWarnings({"NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE"})
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws IOException, java.lang.InterruptedException{
        String token;
        try {
            EnvVars envVars = build.getEnvironment(listener);
            token = envVars.get("SNYK_TOKEN");
            if (token == null){
                listener.getLogger().println("SNYK_TOKEN wasn't found");
                build.setResult(Result.FAILURE);
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            build.setResult(Result.FAILURE);
            return false;
        }

        Result scanResult = scanProject(build, build.getWorkspace(), launcher, listener, token);
        build.setResult(scanResult);
        if (scanResult == Result.SUCCESS) {
            return true;
        }
        return false;
    }

    // From pipeline
    public void perform(@Nonnull Run<?, ?> run,
                        @Nonnull FilePath workspace,
                        @Nonnull Launcher launcher,
                        @Nonnull TaskListener listener,
                        @Nonnull String token) throws InterruptedException, IOException, AbortException {

        Result scanResult = scanProject(run, workspace, launcher, listener, token);
        run.setResult(scanResult);
    }

    public Result scanProject(@Nonnull Run<?, ?> run,
                              @Nonnull FilePath workspace,
                              @Nonnull Launcher launcher,
                              @Nonnull TaskListener listener,
                              @Nonnull String token) throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        String dirPath = workspace.toURI().getPath();

        args.add("docker", "run");
        args.add("--rm", "-v", "/var/run/docker.sock:/var/run/docker.sock",
                "-e", "SNYK_TOKEN=" + token);
        if (this.isMonitor) {
            args.add("-e", "MONITOR=true");
        }
        if ((this.organization != null) && (!this.organization.equals(""))) {
            args.add("-e", "ORGANIZATION=" + this.organization);
        }
        if ((this.targetFile != null) && (!this.targetFile.equals(""))) {
            args.add("-e", "TARGET_FILE=" + this.targetFile);
        }
        if ((this.envFlags != null) && (!this.envFlags.equals(""))) {
            args.add("-e", "ENV_FLAGS=" + this.envFlags);
        }

        String tempDir;
        if (new File("/tmp").exists()) {
            tempDir = "/tmp";
        } else {
            tempDir = System.getProperty("java.io.tmpdir");
        }

        EnvVars envVars = run.getEnvironment(listener);
        String javaRepo = envVars.get("MAVEN_REPO_PATH");
        if ((javaRepo != null) && (!javaRepo.isEmpty())) {
            if (new File(javaRepo).exists()) {
                args.add("-v", javaRepo + ":/home/node/.m2");
            }
        }

        args.add("-v", dirPath + ":/project", "-v", tempDir + ":/tmp", "snyk/snyk", "test", "--json");
        Launcher.ProcStarter ps = launcher.launch();
        ps.cmds(args);
        String command = args.toString();
        listener.getLogger().println(command.replace(token, "*****"));
        ps.stdin(null);
        ps.stderr(listener.getLogger());
        ps.stdout(listener.getLogger());
        ps.quiet(true);
        int exitCode = ps.join();
        listener.getLogger().println("exit code " + String.valueOf(exitCode));
        if (exitCode > 1) {
            return Result.FAILURE;
        }
        FilePath target = new FilePath(workspace, "snyk_report.html");
        FilePath outFilePath = new FilePath(new File(tempDir + "/snyk_report.html"));
        outFilePath.copyTo(target);

        FilePath targetCSS = new FilePath(workspace, "snyk_report.css");
        FilePath outCSS = new FilePath(new File(tempDir + "/snyk_report.css"));
        outCSS.copyTo(targetCSS);

        String artifactName = "snyk_report.html";
        run.addAction(new SnykSecurityAction(run, artifactName));
        archiveArtifacts(run, launcher, listener, workspace);

        if ((exitCode != 0) && (this.getOnFailBuild().equals("true"))) {
            return Result.FAILURE;
        }
        return Result.SUCCESS;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    private void archiveArtifacts(Run<?,?> build, Launcher launcher, TaskListener listener, FilePath workspace )
            throws java.lang.InterruptedException {
        ArtifactArchiver artifactArchiver = new ArtifactArchiver("snyk_report.*");
        artifactArchiver.perform(build, workspace, launcher, listener);
    };

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Snyk Security";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req,formData);
        }
    }
}
