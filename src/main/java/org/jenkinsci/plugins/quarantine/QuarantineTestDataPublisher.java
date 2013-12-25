package org.jenkinsci.plugins.quarantine;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.User;
import hudson.model.Saveable;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestAction;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestObject;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.tasks.Mailer;
import javax.mail.internet.MimeMessage;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.Message;

public class QuarantineTestDataPublisher extends TestDataPublisher {

	@DataBoundConstructor
	public QuarantineTestDataPublisher() {}

	@Override
	public Data getTestData(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener, TestResult testResult) {

		Data data = new Data(build);
		HashMap<String,String> emailsToSend = new HashMap<String,String>();


		for (SuiteResult suite: testResult.getSuites())
		{
			for (CaseResult result: suite.getCases()) {
				QuarantineTestAction previousAction = null;
				CaseResult previous = result.getPreviousResult();

				if (previous != null)
				{
					previousAction = previous.getTestAction(QuarantineTestAction.class);
				}

				// no immediate predecessor (e.g. because job failed or did not run), try and go back in build history
				while (previous == null && build != null)
				{
					build = build.getPreviousCompletedBuild();
					if (build != null)
					{
						listener.getLogger().
							println("no immediate predecessor, but found previous build " + build + ", now try and find " + result.getId());
						hudson.tasks.test.TestResult tr = build.getTestResultAction().findCorrespondingResult(result.getId());
						if (tr != null)
						{
							listener.getLogger().
								println("it is " + tr.getDisplayName());
							previousAction = tr.getTestAction(QuarantineTestAction.class);
							break;
						}
					}
				}

				if (previousAction != null && previousAction.isQuarantined())
				{
					QuarantineTestAction action = new QuarantineTestAction(data, result.getId());
					action.quarantine(previousAction);
					data.addQuarantine(result.getId(), action);

					// send email if failed
					if (!result.isPassed())
					{
						prepareEmail(listener, emailsToSend, action.quarantinedByName());
					}
				}
			}
		}
		sendEmails(listener, emailsToSend);
		return data;
	}

	public String getEmailAddress(BuildListener listener, String username)
	{
		String address = null;
		User u = User.get(username);
		if (u == null)
		{
			listener.getLogger().println("failed obtaining user for name "+username);
			System.out.println("failed obtaining user for name "+username);
			return address;
		}
		Mailer.UserProperty p = u.getProperty(Mailer.UserProperty.class);
		if (p == null)
		{
			listener.getLogger().println("failed obtaining email address for user "+username);
			System.out.println("failed obtaining email address for user "+username);
			return address;
		}

		if (p.getAddress() == null)
		{
			listener.getLogger().println("failed obtaining email address (is null) for user "+username);
			System.out.println("failed obtaining email address (is null) for user "+username);
			return address;
		}

		return p.getAddress();
	}

	public void prepareEmail(BuildListener listener, Map<String,String> emails, String username)
	{
    	String address = getEmailAddress(listener, username);

    	if (address == null)
    	{
    		return;
    	}

    	String message = "";
    	if(emails.containsKey(address))
    	{
    		message += emails.get(address);
    	}
    	message += "foo";

    	emails.put(address,message);
	}

	public void sendEmails(BuildListener listener, Map<String,String> emails)
	{
		for (Map.Entry<String,String> entry: emails.entrySet())
		{
			sendEmail(listener,entry.getKey(),entry.getValue());
		}
	}

    public void sendEmail(BuildListener listener,String address, String message) {
    	MimeMessage msg = new MimeMessage(Mailer.descriptor().createSession());
    	try {
			msg.setRecipients(Message.RecipientType.TO,address);
			msg.setContent(message,"text/html");
	    	Transport.send(msg);
			listener.getLogger().println("[Quarantine]: sent email to " + address);
			System.out.println("q sent email to " + address);
		} catch (MessagingException e) {
			listener.getLogger(). println("[Quarantine]: failed sending email: " + e.toString());
			System.out.println("[Quarantine]: failed sending email: " + e.toString());
		}
    }

	public static class Data extends TestResultAction.Data implements Saveable {

		private Map<String,QuarantineTestAction> quarantines = new HashMap<String,QuarantineTestAction>();

		private final AbstractBuild<?,?> build;

		public Data(AbstractBuild<?,?> build) {
			this.build = build;
		}

		@Override
		public List<TestAction> getTestAction(TestObject testObject) {

			if (build.getParent().getPublishersList().get(QuarantinableJUnitResultArchiver.class) == null)
			{
				// only display if QuarantinableJUnitResultArchiver chosen, to avoid confusion
				System.out.println("not right publisher");
				return Collections.emptyList();
			}

			String id = testObject.getId();
			QuarantineTestAction result = quarantines.get(id);

			if (result != null) {
				return Collections.<TestAction>singletonList(result);
			}

			if (testObject instanceof CaseResult) {
				return Collections.<TestAction>singletonList(new QuarantineTestAction(this, id));
			}
			return Collections.emptyList();
		}

		public boolean isLatestResult()
		{
			return build.getParent().getLastCompletedBuild() == build;
		}

		public hudson.tasks.test.TestResult getResultForTestId(String testObjectId)
		{
			TestResultAction action = build.getAction(TestResultAction.class);
			if (action != null && action.getResult() != null)
			{
				return action.getResult().findCorrespondingResult(testObjectId);
			}
			return null;
		}

		public void save() throws IOException {
			build.save();
		}

		public void addQuarantine(String testObjectId,
				QuarantineTestAction quarantine) {
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
