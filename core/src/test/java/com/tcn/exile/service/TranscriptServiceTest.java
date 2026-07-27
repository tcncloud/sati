package com.tcn.exile.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tcn.exile.service.TranscriptService.CallTranscriptSummary;
import com.tcn.exile.service.TranscriptService.Segment;
import com.tcn.exile.service.TranscriptService.TranscriptThread;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptServiceTest {

  private static final int CUSTOMER = 1;
  private static final int AGENT = 2;

  /** A word lasting 100ms. Words more than a second apart land in separate utterances. */
  private static Segment word(String text, long offsetMillis) {
    return new Segment(text, Duration.ofMillis(offsetMillis), Duration.ofMillis(100));
  }

  private static TranscriptThread thread(int channel, String userId, Segment... words) {
    return new TranscriptThread(channel, userId, List.of(words));
  }

  private static CallTranscriptSummary summary(TranscriptThread... threads) {
    return new CallTranscriptSummary(true, List.of(threads), List.of(), null);
  }

  @Test
  void labelsOneTurnPerSpeaker() {
    var customer = thread(CUSTOMER, "u1", word("hello", 0), word("there", 200));
    var agent = thread(AGENT, "u1", word("hi", 3000), word("yourself", 3200));

    assertEquals(
        "Customer: hello there\nAgent: hi yourself", summary(customer, agent).conversation());
  }

  @Test
  void keepsTurnsWholeWhenSpeakersOverlapWordByWord() {
    // The regression this guards: both speakers talk at once, so ordering raw
    // words by offset alternates the label every word or two.
    var customer = thread(CUSTOMER, "u1", word("okay", 100), word("sure", 500));
    var agent =
        thread(AGENT, "u1", word("just", 0), word("got", 300), word("one", 700), word("in", 900));

    assertEquals(
        "Agent: just got one in\nCustomer: okay sure", summary(customer, agent).conversation());
  }

  @Test
  void startsANewTurnWhenTheOtherSpeakerFillsALongPause() {
    var customer = thread(CUSTOMER, "u1", word("mhm", 2000));
    var agent = thread(AGENT, "u1", word("checking", 0), word("done", 4000));

    assertEquals(
        "Agent: checking\nCustomer: mhm\nAgent: done", summary(customer, agent).conversation());
  }

  @Test
  void mergesWordsSeparatedByLessThanASecondIntoOneUtterance() {
    var agent = thread(AGENT, "u1", word("one", 0), word("two", 1050));

    assertEquals("Agent: one two", summary(agent).conversation());
  }

  @Test
  void putsTheSameSpeakersSeparateUtterancesOnOneLine() {
    var agent = thread(AGENT, "u1", word("first", 0), word("second", 5000));

    assertEquals("Agent: first second", summary(agent).conversation());
  }

  @Test
  void showsAVoiceCarryingAcrossChannelsAsTwoParallelTurns() {
    // Both channels transcribe the same speech when a voice bleeds across
    // them, and nothing upstream dedupes it.
    var customer = thread(CUSTOMER, "u1", word("let", 10), word("me", 210));
    var agent = thread(AGENT, "u1", word("let", 0), word("me", 200));

    assertEquals("Agent: let me\nCustomer: let me", summary(customer, agent).conversation());
  }

  @Test
  void numbersAgentsWhenATransferPutsSeveralLegsOnTheAgentChannel() {
    var customer = thread(CUSTOMER, "u1", word("hi", 2000));
    var first = thread(AGENT, "u1", word("transferring", 0));
    var second = thread(AGENT, "u2", word("continuing", 4000));

    var call = summary(customer, first, second);

    assertEquals(List.of("Customer", "Agent 1", "Agent 2"), call.speakers());
    assertEquals("Agent 1: transferring\nCustomer: hi\nAgent 2: continuing", call.conversation());
  }

  @Test
  void keepsOneCustomerLabelWhenATransferSplitsTheCustomerAcrossThreads() {
    var beforeTransfer = thread(CUSTOMER, "u1", word("still", 1000));
    var afterTransfer = thread(CUSTOMER, "u2", word("here", 3000));
    var first = thread(AGENT, "u1", word("transferring", 0));
    var second = thread(AGENT, "u2", word("continuing", 4000));

    var call = summary(beforeTransfer, afterTransfer, first, second);

    assertEquals(List.of("Customer", "Customer", "Agent 1", "Agent 2"), call.speakers());
    assertEquals(
        "Agent 1: transferring\nCustomer: still here\nAgent 2: continuing", call.conversation());
  }

  @Test
  void leavesTheAgentUnnumberedWhenOneAgentHandledTheWholeCall() {
    var first = thread(AGENT, "u1", word("still", 0));
    var second = thread(AGENT, "u1", word("me", 4000));

    assertEquals(List.of("Agent", "Agent"), summary(first, second).speakers());
  }

  @Test
  void labelsUnrecognizedChannelsUnknown() {
    assertEquals("Unknown: noise", summary(thread(7, "", word("noise", 0))).conversation());
  }

  @Test
  void skipsThreadsWithNoUsableWords() {
    var blank = thread(CUSTOMER, "u1", word("", 0));
    var agent = thread(AGENT, "u1", word("alone", 100));

    assertEquals("Agent: alone", summary(blank, agent).conversation());
  }

  @Test
  void returnsEmptyConversationWhenThereAreNoThreads() {
    assertEquals("", summary().conversation());
    assertEquals(List.of(), summary().speakers());
  }

  @Test
  void joinsEachThreadsOwnWordsWithoutTheOtherChannel() {
    var customer = thread(CUSTOMER, "u1", word("mine", 0), word("only", 4000));
    var agent = thread(AGENT, "u1", word("theirs", 100));

    assertEquals("mine only", customer.text());
    assertEquals("theirs", agent.text());
  }

  @Test
  void attachesContinuationFragmentsToThePrecedingWord() {
    var agent = thread(AGENT, "u1", word("go", 0), word("-ahead", 100));

    assertEquals("go-ahead", agent.text());
    assertEquals("Agent: go-ahead", summary(agent).conversation());
  }
}
