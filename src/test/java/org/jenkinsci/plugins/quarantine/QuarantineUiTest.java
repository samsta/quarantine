package org.jenkinsci.plugins.quarantine;

import com.gargoylesoftware.htmlunit.html.*;
import hudson.model.FreeStyleBuild;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.CaseResult;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.tasks.junit.TestDataPublisher;
import hudson.util.DescribableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class QuarantineUiTest {
   private String projectName = "x";
   private String quarantineText = "quarantineReason";
   private FreeStyleProject project;


   @Rule
   public JenkinsRule j = new JenkinsRule();

   @Before
   public void setUp() throws Exception {
      java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit").setLevel(java.util.logging.Level.SEVERE);

      project = j.createFreeStyleProject(projectName);
      DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> publishers = new DescribableList<>(
              project);
      publishers.add(new QuarantineTestDataPublisher());
      QuarantinableJUnitResultArchiver archiver = new QuarantinableJUnitResultArchiver("*.xml");
      archiver.setTestDataPublishers(publishers);
      project.getPublishersList().add(archiver);

      j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
      j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
   }

   private FreeStyleBuild runBuildWithJUnitResult(final String xmlFileName) throws Exception {
      FreeStyleBuild build;
      project.getBuildersList().add(new TestBuilder() {
         public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                 throws InterruptedException, IOException {
            build.getWorkspace().child("junit.xml").copyFrom(getClass().getResource(xmlFileName));
            return true;
         }
      });
      build = project.scheduleBuild2(0).get();
      project.getBuildersList().clear();
      return build;
   }

   protected TestResult getResultsFromJUnitResult(final String xmlFileName) throws Exception {
      return runBuildWithJUnitResult(xmlFileName).getAction(TestResultAction.class).getResult();
   }

   @Test
   public void testTextSummaryForUnquarantinedTestAuthenticated() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      TestResult tr = build.getAction(TestResultAction.class).getResult();
      HtmlPage page = whenNavigatingToTestCase(tr.getSuite("SuiteA").getCase("TestA"), true);

      assertTrue(pageShowsText(page, "This test was not quarantined. Quarantine it."));
   }

   @Test
   public void testTextSummaryForUnquarantinedTestNotAuthenticated() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      TestResult tr = build.getAction(TestResultAction.class).getResult();
      HtmlPage page = whenNavigatingToTestCase(tr.getSuite("SuiteA").getCase("TestA"), false);

      assertTrue(pageShowsText(page, "This test was not quarantined."));
      assertFalse(pageShowsText(page, "Quarantine it."));
   }

   @Test
   public void testWhenQuarantiningTestSaysQuarantinedBy() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      TestResult tr = build.getAction(TestResultAction.class).getResult();
      HtmlPage page = whenNavigatingToTestCase(tr.getSuite("SuiteA").getCase("TestA"), true);
      whenQuarantiningTestOnPage(page);

      page = whenNavigatingToTestCase(tr.getSuite("SuiteA").getCase("TestA"), false);
      assertTrue(pageShowsText(page, "This test was quarantined by user1"));
   }

   @Test
   public void testCanNavigateToQuarantineReport() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      JenkinsRule.WebClient wc = j.createWebClient();
      wc.login("user1", "user1");
      HtmlPage page = wc.goTo("quarantine/");
      assertNotNull(page);
   }

   private HtmlPage whenNavigatingToTestCase(CaseResult testCase, boolean authenticate) throws Exception {
      JenkinsRule.WebClient wc = j.createWebClient();
      if (authenticate) {
         wc.login("user1", "user1");
      }
      HtmlPage page = wc.goTo(testCase.getOwner().getUrl() + "testReport/" + testCase.getUrl());
      return page;
   }

   private void whenQuarantiningTestOnPage(HtmlPage page) throws Exception {
      (page.getElementById("quarantine")).click();
      HtmlForm form = page.getFormByName("quarantine");
      HtmlTextArea textArea = form.getTextAreaByName("reason");
      textArea.setText(quarantineText);
      HtmlFormUtil.submit(form, j.last(form.getHtmlElementsByTagName("button")));
   }

   private boolean pageShowsText(HtmlPage page, String text) {
      boolean found = page.asText().indexOf(text) != -1;
      System.out.println("didn't find text <" + text + "> in the following page:");
      System.out.println(page.asText());
      return found;
   }

}
