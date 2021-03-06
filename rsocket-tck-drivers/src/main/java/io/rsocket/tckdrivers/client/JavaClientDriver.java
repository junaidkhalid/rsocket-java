/*
 * Copyright 2016 Facebook, Inc.
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations under the License.
 */

package io.rsocket.tckdrivers.client;

import static org.junit.Assert.*;

import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.tckdrivers.common.ConsoleUtils;
import io.rsocket.tckdrivers.common.EchoSubscription;
import io.rsocket.tckdrivers.common.MySubscriber;
import io.rsocket.tckdrivers.common.ParseChannel;
import io.rsocket.tckdrivers.common.ParseChannelThread;
import io.rsocket.tckdrivers.common.ParseMarble;
import io.rsocket.tckdrivers.common.TckIndividualTest;
import io.rsocket.tckdrivers.common.Tuple;
import io.rsocket.transport.ClientTransport;
import io.rsocket.uri.UriTransportRegistry;
import io.rsocket.util.PayloadImpl;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * This class is the driver for the Java RSocket client. To use with class with the current Java
 * impl of RSocket, one should supply both a test file as well as a function that can generate
 * RSockets on demand. This driver will then parse through the test file, and for each test, it will
 * run them on their own thread and print out the results.
 */
public class JavaClientDriver {

  private final Map<String, MySubscriber<Payload>> payloadSubscribers;
  private final Map<String, MySubscriber<Void>> fnfSubscribers;
  private final Map<String, String> idToType;
  private Supplier<RSocket> createClient;
  private final String uri;
  private final String AGENT = "[CLIENT]";
  private ConsoleUtils consoleUtils = new ConsoleUtils(AGENT);

  public JavaClientDriver(String uri) throws FileNotFoundException {
    this.payloadSubscribers = new HashMap<>();
    this.fnfSubscribers = new HashMap<>();
    this.idToType = new HashMap<>();
    this.uri = uri;

    // creating a client object to run the test
    try {

      RSocket client = createClient(this.uri);
      this.createClient = () -> client;

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public enum TestResult {
    PASS,
    FAIL,
    CHANNEL
  }

  /**
   * A function that creates a RSocket on a new TCP connection.
   *
   * @return a RSocket
   */
  public RSocket createClient(String uri) {
      return RSocketFactory.connect().transport(UriTransportRegistry.clientForUri(uri)).start().block();
  }

  /**
   * Parses through the commands for each test, and calls handlers that execute the commands.
   *
   * @param test the list of strings which makes up each test case
   * @param name the name of the test
   * @return an option with either true if the test passed, false if it failed, or empty if no
   *     subscribers were found
   */
  public void runTest(List<String> test, String name) throws Exception {
    List<String> id = new ArrayList<>();
    Iterator<String> iter = test.iterator();
    boolean channelTest = false; // tells whether this is a test for channel or not
    while (iter.hasNext()) {
      String line = iter.next();
      String[] args = line.split("%%");
      if (args[0].equals("EOF")) {
        handleEOF();
        continue;
      }
      switch (args[1]) {
        case "subscribe":
          handleSubscribe(args);
          id.add(args[3]);
          break;
        case "channel":
          channelTest = true;
          handleChannel(args, iter, name, true);
          break;
        case "echochannel":
          handleEchoChannel(args);
          break;
        case "await":
          switch (args[2]) {
            case "terminal":
              handleAwaitTerminal(args);
              break;
            case "atLeast":
              handleAwaitAtLeast(args);
              break;
            case "no_events":
              handleAwaitNoEvents(args);
              break;
            default:
              break;
          }
          break;

        case "assert":
          switch (args[2]) {
            case "no_error":
              assertNoError(args);
              break;
            case "error":
              assertError(args);
              break;
            case "received":
              assertReceived(args);
              break;
            case "received_n":
              assertReceivedN(args);
              break;
            case "received_at_least":
              assertReceivedAtLeast(args);
              break;
            case "completed":
              assertCompleted(args);
              break;
            case "no_completed":
              assertNoCompleted(args);
              break;
            case "canceled":
              assertCancelled(args);
              break;
          }
          break;
        case "take":
          handleTake(args);
          break;
        case "request":
          handleRequest(args);
          break;
        case "cancel":
          handleCancel(args);
          break;
        default:
          // the default behavior is to just skip the line, so we can acommodate slight changes to the TCK
          break;
      }
    }
    // this check each of the subscribers to see that they all passed their assertions

    assertTrue("There is no subscriber in this test", (channelTest) || (id.size() > 0));
  }

  /**
   * This function takes in the arguments for the subscribe command, and subscribes an instance of
   * MySubscriber with an initial request of 0 (which means don't immediately make a request) to an
   * instance of the corresponding publisher
   *
   * @param args
   */
  private void handleSubscribe(String[] args) {
    switch (args[2]) {
      case "rr":
        MySubscriber<Payload> rrsub = new MySubscriber<>(0L, AGENT);
        payloadSubscribers.put(args[3], rrsub);
        idToType.put(args[3], args[2]);
        RSocket rrclient = createClient.get();
        consoleUtils.info("Sending RR with " + args[4] + " " + args[5]);
        Publisher<Payload> rrpub = rrclient.requestResponse(new PayloadImpl(args[4], args[5]));
        rrpub.subscribe(rrsub);
        break;
      case "rs":
        MySubscriber<Payload> rssub = new MySubscriber<>(0L, AGENT);
        payloadSubscribers.put(args[3], rssub);
        idToType.put(args[3], args[2]);
        RSocket rsclient = createClient.get();
        consoleUtils.info("Sending RS with " + args[4] + " " + args[5]);
        Publisher<Payload> rspub = rsclient.requestStream(new PayloadImpl(args[4], args[5]));
        rspub.subscribe(rssub);
        break;
      case "fnf":
        MySubscriber<Void> fnfsub = new MySubscriber<>(0L, AGENT);
        fnfSubscribers.put(args[3], fnfsub);
        idToType.put(args[3], args[2]);
        RSocket fnfclient = createClient.get();
        consoleUtils.info("Sending fnf with " + args[4] + " " + args[5]);
        Publisher<Void> fnfpub = fnfclient.fireAndForget(new PayloadImpl(args[4], args[5]));
        fnfpub.subscribe(fnfsub);
        break;
      default:
        break;
    }
  }

  /**
   * This function takes in an iterator that is parsing through the test, and collects all the parts
   * that make up the channel functionality. It then create a thread that runs the test, which we
   * wait to finish before proceeding with the other tests.
   *
   * @param args
   * @param iter
   * @param name
   */
  private void handleChannel(String[] args, Iterator<String> iter, String name, boolean pass) {
    List<String> commands = new ArrayList<>();
    String line = iter.next();
    // channel script should be bounded by curly braces
    while (!line.equals("}")) {
      commands.add(line);
      line = iter.next();
    }
    // set the initial payload
    Payload initialPayload = new PayloadImpl(args[2], args[3]);

    // this is the subscriber that will request data from the server, like all the other test subscribers
    MySubscriber<Payload> testsub = new MySubscriber<>(1L, AGENT);
    CountDownLatch c = new CountDownLatch(1);

    // we now create the publisher that the server will subscribe to with its own subscriber
    // we want to give that subscriber a subscription that the client will use to send data to the server
    RSocket client = createClient.get();
    AtomicReference<ParseChannelThread> mypct = new AtomicReference<>();
    Publisher<Payload> pub =
        client.requestChannel(
            new Publisher<Payload>() {
              @Override
              public void subscribe(Subscriber<? super Payload> s) {
                ParseMarble pm = new ParseMarble(s, AGENT);
                TestSubscription ts = new TestSubscription(pm, initialPayload, s);
                s.onSubscribe(ts);
                ParseChannel pc = new ParseChannel(commands, testsub, pm, name, pass, AGENT);
                ParseChannelThread pct = new ParseChannelThread(pc);
                pct.start();
                mypct.set(pct);
                c.countDown();
              }
            });
    pub.subscribe(testsub);
    try {
      c.await();
    } catch (InterruptedException e) {
      consoleUtils.info("interrupted");
    }
    mypct.get().join();
  }

  /**
   * This handles echo tests. This sets up a channel connection with the EchoSubscription, which we
   * pass to the MySubscriber.
   *
   * @param args
   */
  private void handleEchoChannel(String[] args) {
    Payload initPayload = new PayloadImpl(args[2], args[3]);
    MySubscriber<Payload> testsub = new MySubscriber<>(1L, AGENT);
    RSocket client = createClient.get();
    Publisher<Payload> pub =
        client.requestChannel(
            new Publisher<Payload>() {
              @Override
              public void subscribe(Subscriber<? super Payload> s) {
                EchoSubscription echoSub = new EchoSubscription(s);
                s.onSubscribe(echoSub);
                testsub.setEcho(echoSub);
                s.onNext(initPayload);
              }
            });
    pub.subscribe(testsub);
  }

  private void handleAwaitTerminal(String[] args) {
    consoleUtils.info("Awaiting at Terminal");
    String id = args[3];

    assertNotEquals("Could not find subscriber with given id", idToType.get(id), null);

    if (idToType.get(id).equals("fnf")) {
      MySubscriber<Void> sub = fnfSubscribers.get(id);
      assertTrue(sub.awaitTerminalEvent());
    } else {
      MySubscriber<Payload> sub = payloadSubscribers.get(id);
      assertTrue(sub.awaitTerminalEvent());
    }
  }

  private void handleAwaitAtLeast(String[] args) {
    consoleUtils.info("Awaiting at Terminal for at least " + args[4]);
    try {
      String id = args[3];
      MySubscriber<Payload> sub = payloadSubscribers.get(id);
      sub.awaitAtLeast(Long.parseLong(args[4]));
    } catch (InterruptedException e) {
      assertNull("interrupted ", e.getMessage());
    }
  }

  private void handleAwaitNoEvents(String[] args) {
    try {
      String id = args[3];
      MySubscriber<Payload> sub = payloadSubscribers.get(id);
      sub.awaitNoEvents(Long.parseLong(args[4]));
    } catch (InterruptedException e) {
      assertNull("interrupted ", e.getMessage());
    }
  }

  private void assertNoError(String[] args) {
    String id = args[3];

    assertNotNull("Could not find subscriber with given id", idToType.get(id));
    if (idToType.get(id).equals("fnf")) {
      MySubscriber<Void> sub = fnfSubscribers.get(id);
      try {
        sub.assertNoErrors();
      } catch (Throwable ex) {
        assertNull(ex.getMessage());
      }
    } else {
      MySubscriber<Payload> sub = payloadSubscribers.get(id);
      try {
        sub.assertNoErrors();
      } catch (Throwable ex) {
        assertNull(ex.getMessage());
      }
    }
  }

  private void assertError(String[] args) {
    consoleUtils.info("Checking for error");
    String id = args[3];
    assertNotNull("Could not find subscriber with given id", idToType.get(id));
    if (idToType.get(id).equals("fnf")) {
      MySubscriber<Void> sub = fnfSubscribers.get(id);
      sub.myAssertError(new Throwable());
    } else {
      MySubscriber<Payload> sub = payloadSubscribers.get(id);
      sub.myAssertError(new Throwable());
    }
  }

  private void assertReceived(String[] args) {
    consoleUtils.info("Verify we received " + args[4]);
    String id = args[3];
    MySubscriber<Payload> sub = payloadSubscribers.get(id);
    String[] values = args[4].split("&&");
    List<Tuple<String, String>> assertList = new ArrayList<>();
    for (String v : values) {
      String[] vals = v.split(",");
      assertList.add(new Tuple<>(vals[0], vals[1]));
    }
    sub.assertValues(assertList);
  }

  private void assertReceivedN(String[] args) {
    String id = args[3];
    MySubscriber<Payload> sub = payloadSubscribers.get(id);
    try {
      sub.assertValueCount(Integer.parseInt(args[4]));
    } catch (Throwable ex) {
      assertNull(ex.getMessage());
    }
  }

  private void assertReceivedAtLeast(String[] args) {
    String id = args[3];
    MySubscriber<Payload> sub = payloadSubscribers.get(id);
    sub.assertReceivedAtLeast(Integer.parseInt(args[4]));
  }

  private void assertCompleted(String[] args) {
    consoleUtils.info("Handling onComplete");
    String id = args[3];

    assertNotNull("Could not find subscriber with given id", idToType.get(id));
    if (idToType.get(id).equals("fnf")) {
      MySubscriber<Void> sub = fnfSubscribers.get(id);
      try {
        sub.assertComplete();
      } catch (Throwable ex) {
        assertNull(ex.getMessage());
      }
    } else {
      MySubscriber<Payload> sub = payloadSubscribers.get(id);
      try {
        sub.assertComplete();
      } catch (Throwable ex) {
        assertNull(ex.getMessage());
      }
    }
  }

  private void assertNoCompleted(String[] args) {
    consoleUtils.info("Handling NO onComplete");
    String id = args[3];

    assertNotNull("Could not find subscriber with given id", idToType.get(id));
    if (idToType.get(id).equals("fnf")) {
      MySubscriber<Void> sub = fnfSubscribers.get(id);
      try {
        sub.assertNotComplete();
      } catch (Throwable ex) {
        assertNull(ex.getMessage());
      }
    } else {
      MySubscriber<Payload> sub = payloadSubscribers.get(id);
      try {
        sub.assertNotComplete();
      } catch (Throwable ex) {
        assertNull(ex.getMessage());
      }
    }
  }

  private void assertCancelled(String[] args) {
    String id = args[3];
    MySubscriber<Payload> sub = payloadSubscribers.get(id);
    assertTrue(sub.isCancelled());
  }

  private void handleRequest(String[] args) {
    Long num = Long.parseLong(args[2]);
    String id = args[3];

    assertNotNull("Could not find subscriber with given id", idToType.get(id));
    if (idToType.get(id).equals("fnf")) {
      MySubscriber<Void> sub = fnfSubscribers.get(id);
      consoleUtils.info("ClientDriver: Sending request for " + num);
      sub.request(num);
    } else {
      MySubscriber<Payload> sub = payloadSubscribers.get(id);
      consoleUtils.info("ClientDriver: Sending request for " + num);
      sub.request(num);
    }
  }

  private void handleTake(String[] args) {
    String id = args[3];
    Long num = Long.parseLong(args[2]);
    MySubscriber<Payload> sub = payloadSubscribers.get(id);
    sub.take(num);
  }

  private void handleCancel(String[] args) {
    String id = args[2];
    MySubscriber<Payload> sub = payloadSubscribers.get(id);
    sub.cancel();
  }

  private void handleEOF() {
    MySubscriber<Void> fnfsub = new MySubscriber<>(0L, AGENT);
    RSocket fnfclient = createClient.get();
    Publisher<Void> fnfpub = fnfclient.fireAndForget(new PayloadImpl("shutdown", "shutdown"));
    fnfpub.subscribe(fnfsub);
    fnfsub.request(1);
  }

  /** A subscription for channel, it handles request(n) by sort of faking an initial payload. */
  private class TestSubscription implements Subscription {
    private boolean firstRequest = true;
    private ParseMarble pm;
    private Payload initPayload;
    private Subscriber<? super Payload> sub;

    public TestSubscription(ParseMarble pm, Payload initpayload, Subscriber<? super Payload> sub) {
      this.pm = pm;
      this.initPayload = initpayload;
      this.sub = sub;
    }

    @Override
    public void cancel() {
      pm.cancel();
    }

    @Override
    public void request(long n) {
      consoleUtils.info("TestSubscription: request " + n);
      long m = n;
      if (firstRequest) {
        sub.onNext(initPayload);
        firstRequest = false;
        m = m - 1;
      }
      if (m > 0) pm.request(m);
    }
  }

  /**
   * A function that parses the file and extract the individual tests
   *
   * @param file The file to read as input.
   * @return a list of TckIndividualTest.
   */
  public static List<TckIndividualTest> extractTests(File file) throws Exception {

    BufferedReader reader = new BufferedReader(new FileReader(file));
    List<TckIndividualTest> tests = new ArrayList<>();
    List<String> test = new ArrayList<>();
    String line = reader.readLine();
    String testFile = file.getName().replaceFirst(TckIndividualTest.clientPrefix, "");

    //Parsing the input client file to read all the tests
    while (line != null) {
      switch (line) {
        case "!":
          String name = "";
          if (test.size() > 1) {
            name = test.get(0).split("%%")[1];
          }

          TckIndividualTest tckTest = new TckIndividualTest(name, test, testFile);
          tests.add(tckTest);
          test = new ArrayList<>();
          break;
        default:
          test.add(line);
          break;
      }
      line = reader.readLine();
    }

    if (test.size() > 0) {
      String name = "";
      name = test.get(0).split("%%")[1];
      TckIndividualTest tckTest = new TckIndividualTest(name, test, testFile);
      tests.add(tckTest);
      tests = tests.subList(1, tests.size()); // remove the first list, which is empty
    }
    return tests;
  }
}
