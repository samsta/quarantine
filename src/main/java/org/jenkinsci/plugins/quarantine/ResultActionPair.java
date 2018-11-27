package org.jenkinsci.plugins.quarantine;

import hudson.tasks.junit.CaseResult;

// This inner class needs to be public as
// it is passed into the ctx where it needs to be executed by the script that renders the email
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
