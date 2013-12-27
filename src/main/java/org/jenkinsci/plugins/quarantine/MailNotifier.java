package org.jenkinsci.plugins.quarantine;

import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.User;
import hudson.tasks.Mailer;
import hudson.tasks.junit.CaseResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.jelly.JellyContext;
import org.apache.commons.jelly.JellyException;
import org.apache.commons.jelly.Script;
import org.apache.commons.jelly.XMLOutput;
import org.xml.sax.InputSource;

import jenkins.model.JenkinsLocationConfiguration;

public class MailNotifier {

   public class ResultActionPair {
      private final CaseResult result;
      private final QuarantineTestAction action;

      public ResultActionPair(CaseResult result, QuarantineTestAction action) {
         this.result = result;
         this.action = action;
      }

      public CaseResult getResult() {
         return result;
      }

      public QuarantineTestAction getAction() {
         return action;
      }
   }

   HashMap<String, List<ResultActionPair>> emailsToSend = new HashMap<String, List<ResultActionPair>>();
   PrintStream logger;

   public MailNotifier(BuildListener build_listener) {
      logger = build_listener.getLogger();
   }

   private void println(String msg) {
      logger.print(msg);
      // System.out.println(msg);
   }

   public void addResult(CaseResult result, QuarantineTestAction action) {
      String username = action.quarantinedByName();

      if (!emailsToSend.containsKey(username)) {
         emailsToSend.put(username, new ArrayList<ResultActionPair>());
      }
      emailsToSend.get(username).add(new ResultActionPair(result, action));

   }

   public String getEmailAddress(String username) {
      String address = null;
      User u = User.get(username);
      if (u == null) {
         println("failed obtaining user for name " + username);
         return address;
      }
      Mailer.UserProperty p = u.getProperty(Mailer.UserProperty.class);
      if (p == null) {
         println("failed obtaining email address for user " + username);
         return address;
      }

      if (p.getAddress() == null) {
         println("failed obtaining email address (is null) for user " + username);
         return address;
      }

      return p.getAddress();
   }

   public void sendEmails() {
      for (Map.Entry<String, List<ResultActionPair>> entry : emailsToSend.entrySet()) {
         sendEmail(entry.getKey(), entry.getValue());
      }
   }

   private String renderEmail(String username, List<ResultActionPair> results) {
      ByteArrayOutputStream output;
      try {
         Script script;
         JellyContext ctx = new JellyContext();
         ctx.setVariable("user", username);
         ctx.setVariable("results", results);
         ctx.setVariable("rootURL", Hudson.getInstance().getRootUrl());
         InputStream template = getClass().getResourceAsStream("MailNotifier/message.jelly");
         script = ctx.compileScript(new InputSource(template));
         if (script == null) {
            println("[Quarantine]: failed compiling jelly script");
            return null;
         }
         output = new ByteArrayOutputStream(16 * 1024);
         XMLOutput xmlOutput = XMLOutput.createXMLOutput(output);
         script.run(ctx, xmlOutput);
         xmlOutput.flush();
         xmlOutput.close();
         output.close();
      } catch (JellyException e) {
         println("[Quarantine]: failed compiling jelly: " + e.toString());
         return null;
      } catch (IOException e) {
         println("[Quarantine]: converting jelly: " + e.toString());
         return null;
      }
      return output.toString();
   }

   public void sendEmail(String username, List<ResultActionPair> results) {
      String address = getEmailAddress(username);
      if (address == null) {
         return;
      }

      MimeMessage msg = new MimeMessage(Mailer.descriptor().createSession());
      try {

         msg.setFrom(new InternetAddress(JenkinsLocationConfiguration.get().getAdminAddress()));
         msg.setSentDate(new Date());
         msg.setRecipients(Message.RecipientType.TO, address);
         msg.setSubject("Failure of quarantined tests");

         String message = renderEmail(username, results);
         if (message == null) {
            println("[Quarantine]: unable to render message");
            return;
         }
         msg.setContent(message, "text/html");

         Transport.send(msg);

         println("[Quarantine]: sent email to " + address);
      } catch (MessagingException e) {
         println("[Quarantine]: failed sending email: " + e.toString());
      }
   }

}
