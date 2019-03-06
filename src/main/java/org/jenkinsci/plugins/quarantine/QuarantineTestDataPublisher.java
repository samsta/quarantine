package org.jenkinsci.plugins.quarantine;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.junit.*;
import hudson.tasks.test.AbstractTestResultAction;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;

public class QuarantineTestDataPublisher extends TestDataPublisher {

   @DataBoundConstructor
   public QuarantineTestDataPublisher() {
   }

   @Override
   public Data contributeTestData(Run<?, ?> run, @Nonnull FilePath workspace, Launcher launcher,
                                  TaskListener listener, TestResult testResult) {
      Data data = new Data(run);

      MailNotifier notifier = new MailNotifier(listener);

      for (SuiteResult suite : testResult.getSuites()) {
         for (CaseResult result : suite.getCases()) {
            QuarantineTestAction previousAction = null;
            CaseResult previous = result.getPreviousResult();
            Run previousBuild = run.getPreviousCompletedBuild();

            if (previous != null) {
               previousAction = previous.getTestAction(QuarantineTestAction.class);
            }

            // no immediate predecessor (e.g. because job failed or did not
            // run), try and go back in build history
            while (previous == null && previousBuild != null) {
               if (previousBuild.getAction(AbstractTestResultAction.class) != null) {
                  hudson.tasks.test.TestResult tr = null;
                  try {
                     tr = previousBuild.getAction(AbstractTestResultAction.class).findCorrespondingResult(
                             result.getId());
                  }
                  catch (Exception e){
                     listener.getLogger().println("could not find result for id " + result.getId() + " in build " + previousBuild + ": " + e.getMessage());
                  }
                  if (tr != null) {
                     listener.getLogger().println("found " + tr.getDisplayName() + " in build " + previousBuild);
                     previousAction = tr.getTestAction(QuarantineTestAction.class);
                     break;
                  }
               }
               else
               {
                  listener.getLogger().println("build " + previousBuild + " does not have test results");
               }
               previousBuild = previousBuild.getPreviousCompletedBuild();
            }

            if (previousAction != null && previousAction.isQuarantined()) {
               QuarantineTestAction action = new QuarantineTestAction(data, result.getId());
               action.quarantine(previousAction);
               data.addQuarantine(result.getId(), action);

               // send email if failed
               if (!result.isPassed()) {
                  notifier.addResult(result, action);
               }
            }
         }
      }
      notifier.sendEmails();
      return data;


   }

   public static class Data extends TestResultAction.Data implements Saveable {

      private Map<String, QuarantineTestAction> quarantines = new HashMap<>();

      private final Run<?, ?> build;

      Data(Run<?, ?> build) {
         this.build = build;
      }

      @Override
      public List<TestAction> getTestAction(@SuppressWarnings("deprecation")TestObject testObject) {

         if ((build.getParent() instanceof Project))
         {
            Project project = (Project) build.getParent();
            if (project != null &&  project.getPublishersList().get(QuarantinableJUnitResultArchiver.class) == null) {
               // only display if QuarantinableJUnitResultArchiver chosen, to avoid
               // confusion
               return Collections.emptyList();
         }}

         final String prefix = "junit";
         String id = testObject.getId();
         QuarantineTestAction result = quarantines.get(id);

         // In Hudson 1.347 or so, IDs changed, and a junit/ prefix was added.
         // Attempt to fix this backward-incompatibility
         if (result == null && id.startsWith(prefix)) {
            result = quarantines.get(id.substring(prefix.length()));
         }

         if (result != null) {
            return Collections.singletonList(result);
         }

         if (testObject instanceof CaseResult) {
            return Collections.singletonList(new QuarantineTestAction(this, id));
         }
         return Collections.emptyList();
      }

      boolean isLatestResult() {
         return build.getParent().getLastCompletedBuild() == build;
      }

      public hudson.tasks.test.TestResult getResultForTestId(String testObjectId) {
         TestResultAction action = build.getAction(TestResultAction.class);
         if (action != null && action.getResult() != null) {
            return action.getResult().findCorrespondingResult(testObjectId);
         }
         return null;
      }

      public void save() throws IOException {
         build.save();
      }

      public void addQuarantine(String testObjectId, QuarantineTestAction quarantine) {
         quarantines.put(testObjectId, quarantine);
      }

   }

   @Extension
   public static class DescriptorImpl extends Descriptor<TestDataPublisher> {

      public String getHelpFile() {
         return "/plugin/quarantine/help.html";
      }

      @Override
      public String getDisplayName() {
         return Messages.QuarantineTestDataPublisher_DisplayName();
      }
   }

}
