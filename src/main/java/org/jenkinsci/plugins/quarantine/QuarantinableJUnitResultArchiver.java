package org.jenkinsci.plugins.quarantine;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.Result;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.TestResultAction.Data;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.JUnitResultArchiver;

import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class QuarantinableJUnitResultArchiver extends JUnitResultArchiver {

	@DataBoundConstructor
	public QuarantinableJUnitResultArchiver(String testResults) {
		super(testResults);
	}

	/**
	 * Because build results can only be made worse, we can't just run another
	 * recorder straight after the JUnitResultArchiver. So we clone-and-own the
	 * {@link JUnitResultArchiver#perform(AbstractBuild, Launcher, BuildListener)}
	 * method here so we can inspect the quarantine before making the PASS/FAIL
	 * decision
	 *
	 * The build is only failed if there are failing tests that have not been put
	 * in quarantine
	 */
	@Override
	public void perform(Run build, FilePath workspace, Launcher launcher,
			TaskListener listener) throws InterruptedException, IOException {
				
		listener.getLogger().println(hudson.tasks.junit.Messages.JUnitResultArchiver_Recording());

		final String testResults = build.getEnvironment(listener).expand(getTestResults());

		// ideally, we'd use parse() here, but it's been made private... :-(
		TestResult result = new JUnitParser(isKeepLongStdio()).parseResult(testResults, build, workspace, launcher, listener);

		synchronized (build) {
			TestResultAction action = build.getAction(TestResultAction.class);
			try {
				action = new TestResultAction(build, result, listener);
			} catch (NullPointerException npe) {
				throw new AbortException(Messages.QuarantinableJUnitResultArchiver_BadXML(testResults));
			}
			result.freeze(action);
			action.setHealthScaleFactor(getHealthScaleFactor()); // overwrites previous value if appending
			if (result.isEmpty()) {
				if (build.getResult() == Result.FAILURE) {
					// most likely a build failed before it gets to the test phase.
					// don't report confusing error message.
					return;
				}
				// most likely a configuration error in the job - e.g. false pattern to match the JUnit result files
				throw new AbortException(hudson.tasks.junit.Messages.JUnitResultArchiver_ResultIsEmpty());
			}

			// TODO: Move into JUnitParser [BUG 3123310]
			// FIXME: ideally, we'd use action.getData() so we can add to the data, but it's not accessible.
			//   create a new list of data - not sure what the implications are, but that's how the quarantine
			//   plugin worked before
			List<Data> data = new ArrayList<Data>();
			if (getTestDataPublishers() != null) {
				for (TestDataPublisher tdp : getTestDataPublishers()) {
					Data d = tdp.contributeTestData(build, workspace, launcher, listener, result);
					if (d != null) {
						data.add(d);
					}
				}
				action.setData(data);
			}

			build.addAction(action);

			if (action.getResult().getFailCount() > 0)
			{
				int quarantined = 0;
				for (CaseResult case_result : action.getResult().getFailedTests()) {
					QuarantineTestAction quarantineAction = case_result.getTestAction(QuarantineTestAction.class);
					if (quarantineAction != null) {
						if (quarantineAction.isQuarantined()) {
							listener.getLogger().println("[Quarantine]: " + case_result.getFullName() + " failed but is quarantined");
							quarantined++;
						}
					}
				}

				int remaining = action.getResult().getFailCount() - quarantined;
				listener.getLogger().println("[Quarantine]: " + remaining + " unquarantined failures remaining");
				
				if (remaining > 0)
					build.setResult(Result.UNSTABLE);
			}
		}
	}

	@Extension
	public static class DescriptorImpl extends JUnitResultArchiver.DescriptorImpl {
		public String getDisplayName() {
			return Messages.QuarantinableJUnitResultArchiver_DisplayName();
		}
	}
}
