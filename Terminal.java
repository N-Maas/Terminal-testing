import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An alternative Terminal class that additionally provides possibilities for automatic testing. All
 * methods of the original Terminal class and many additional testing methods are implemented. All
 * tests should be invoked by separate testing classes. (Note: when used for testing the usual
 * functionality is disabled.)
 * <p>
 * Every test <b>must</b> start with calling {@code Terminal.initTestSession(Runnable main)}.
 * Afterwards various testing methods can be called: {@code nextInput(String input)} and
 * {@code nextOutput()} provide the basic input/output functionality. The {@code assert} and
 * {@code test} methods compare the real and expected output of the tested program. For example,
 * {@code assertOutput(String expected)} will effectively invoke {@code nextOutput()} and compare
 * the result to the {@code expected} parameter. The difference between {@code assert} and
 * {@code test} is, that {@code test} methods additionally invoke {@code nextInput()} with a given
 * input String, so that giving input and testing output is combined in one method. Apart from
 * testing output, it is possible to test whether the program terminates or whether a specified
 * exception occurs.
 * <p>
 * The behavior of the tests can be specified by so called policies. Dependent on the
 * {@code printPolicy} the input and output and/or failure messages will be printed to
 * {@code System.out}, or not. The {@code cancelPolicy} determines whether the tests will be
 * canceled or continued at failures. And {@code setTimeOut(long millis)} specifies how long the
 * test will wait for answers.
 * <p>
 * By {@code enforceExit()} the termination of the tested program can be enforced. For this method
 * and canceling working properly, the tested program <b>must not</b> catch general
 * RuntimeExceptions (or a supertype) thrown by {@code readLine()} or a print method. An additional
 * utility is provided by {@code runCancelingTest(Runnable test)}, which enables to run a test
 * method, that will be canceled at failures, without canceling the entire test.
 * <p>
 *
 * Code Example: <blockquote>
 *
 * <pre>
 * <code>
 * import static terminal.Terminal.*;
 *
 * public class StudyPortalTest {
 *      public static void basicTest() {
 *              initTestSession(() -&gt; StudyPortal.main(new String[0]));
 *              testOutput("add-student max;mustermann;123456", "Ok");
 *              testOutput("add-student albert;albertus;654321", "Ok");
 *
 *              nextInput("list-student");
 *              assertOutput("123456 max mustermann none");
 *              assertOutput("654321 albert albertus none");
 *
 *              // alternatively:
 *              testList("list-student", "123456 max mustermann none", "654321 albert albertus none");
 *
 *              testPrefix("add-lecture la", "Error, ");
 *              testPrefix("Identical matriculation numbers are not permitted.",
 *                      "add-student maximilia;mustermann;123456", "Error, ");
 *
 *              if (!testExit("quit")) {
 *                      // not really necessary, as starting a new test will terminate the preceding one
 *                      enforceExit();
 *              }
 *      }
 *
 *      public static void main(String[] args) {
 *              setPrintPolicy(PRINT_IN_OUT);
 *              runCancelingTest(() -&gt; basicTest(), "Basic test canceled.");
 *      }
 * }
 * </code>
 * </pre>
 *
 * </blockquote>
 */
public class Terminal {
    /**
     * PrintPolicy of printing nothing through {@code System.out}
     */
    public static final int PRINT_NONE = 0;
    /**
     * PrintPolicy of printing the regular input and output, but not printing failures
     */
    public static final int PRINT_IN_OUT = 1;
    /**
     * PrintPolicy of printing the failures, but without input or output
     */
    public static final int PRINT_FAILURES = 2;
    /**
     * PrintPolicy of printing both input/output and failures through {@code System.out} (default
     * policy)
     */
    public static final int PRINT_ALL = 3;
    /**
     * CancelPolicy of never canceling the test
     */
    public static final int CANCEL_NEVER = 6;
    /**
     * CancelPolicy of canceling the test only at mismatches of in/out order
     */
    public static final int CANCEL_AT_MISMATCH = 7;
    /**
     * CancelPolicy of canceling the test at failures and in/out mismatches
     */
    public static final int CANCEL_ALWAYS = 8;

    /**
     * Reads text from the "standard" input stream, buffering characters so as to provide for the
     * efficient reading of characters, arrays, and lines. This stream is already open and ready to
     * supply input data and corresponds to keyboard input.
     */
    private static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in));

    private static final SynchronousQueue<String> transferQueue = new SynchronousQueue<>();
    private static final SynchronousQueue<Throwable> exitQueue = new SynchronousQueue<>();
    private static CyclicBarrier barrier = new CyclicBarrier(2);
    private static Thread testThread = new Thread("test thread");
    private static int printPolicy = 3, cancelPolicy = 6;
    private static long timeOut = 100;
    private static boolean isTesting = false;

    private static class ExitException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final boolean interrupted;

        public ExitException() {
            super();
            this.interrupted = true;
        }

        public ExitException(boolean interrupt, String message) {
            super(message);
            this.interrupted = interrupt;
        }
    }

    /**
     * Exception indicating that the test has been canceled.
     */
    public static class CancelException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CancelException() {
            super();
        }
    }

    /**
     * Private constructor to avoid object generation.
     *
     * @deprecated Utility-class constructor.
     */
    @Deprecated
    private Terminal() {
        throw new AssertionError("Utility class constructor.");
    }

    private static void printInOut(String s) {
        if ((printPolicy & 1) != 0) {
            System.out.println(s);
        }
    }

    private static void reportError(String message, boolean isMismatch) {
        if (printPolicy > 1) {
            System.out.println((isMismatch ? ">>> MISMATCH: " : ">>> FAILURE: ") + message);
        }
        if (cancelPolicy >= 8 || (isMismatch && cancelPolicy >= 7)) {
            enforceExit();
            throw new CancelException();
        }
    }

    private static boolean assertString(String message, boolean prefix, boolean randomOrder, String... expected) {
        boolean result = true;
        for (int i = 0; i < expected.length; i++) {
            String out = nextOutput(message);
            if (out == null) {
                return false;
            }
            int pos = -1, max = randomOrder ? expected.length : i + 1;
            for (int j = i; j < max; j++) {
                if (prefix ? out.startsWith(expected[j]) : out.equals(expected[j])) {
                    pos = j;
                    if (i != j) {
                        String puffer = expected[i];
                        expected[i] = expected[j];
                        expected[j] = puffer;
                    }
                }
            }
            if (pos == -1) {
                reportError(message + "\n>>> Expected: " + expected[i], false);
                result = false;
            }
        }
        return result;
    }

    /**
     * This policy determines what will be print through {@code System.out}. Parameters must be
     * element of {@code Terminal.{PRINT_NONE, PRINT_IN_OUT, PRINT_FAILURES, PRINT_ALL}}. For
     * details see the documentation of the constants.
     * <p>
     * Default value: {@code PRINT_ALL}
     *
     * @param policy the printPolicy to be set
     * @see #PRINT_NONE
     */
    public static void setPrintPolicy(int policy) {
        if (policy < 0 || policy > 3) {
            throw new IllegalArgumentException("Illegal printPolicy value.");
        }
        printPolicy = policy;
    }

    /**
     * This policy determines whether the test will be canceled after failures. For canceling, the
     * testing method will throw a {@code Terminal.CancelException}.
     * <p>
     * It is considered a difference between usual failures (meaning that expected and received
     * <i>value</i> of output differs; "FAILURE" message) and mismatches (meaning that expected and
     * received <i>order</i> of input/output differs; "MISMATCH" message). So the
     * {@code CANCEL_AT_MISMATCHES} policy will cancel tests at mismatches, but not at usual
     * failures.
     * <p>
     * Default value: {@code CANCEL_NEVER}
     *
     * @param policy whether the test will be canceled at failures
     * @see #CANCEL_NEVER
     */
    public static void setCancelPolicy(int policy) {
        if (policy < 6 || policy > 8) {
            throw new IllegalArgumentException("Illegal cancelPolicy value.");
        }
        cancelPolicy = policy;
    }

    /**
     * This method determines mainly, how long the test will wait for responses of the tested
     * program. The default value should work well for most purposes. Increase the value if
     * expecting very time-expensive calculations, decrease the value for making the test fail more
     * quickly (and so needing less time).
     * <p>
     * More specific, the method additionally determines that vice versa the tested program will
     * wait the double amount of time for input or acceptance of output (which value is generally of
     * less importance).
     * <p>
     * Default value: {@code 100}
     *
     * @param millis the number of milliseconds the test will wait for responses
     */
    public static void setTimeOut(long millis) {
        if (millis < 1) {
            throw new IllegalArgumentException("TimeOut value must be positive.");
        }
        timeOut = millis;
    }

    /**
     * Starts a test session. A previous test that is still running will be terminated. For
     * specifying what to test, a {@code Runnable} is required as parameter, that invokes the
     * (probably main-) method to be tested.
     * <p>
     * Example:
     *
     * <pre>
     * Terminal.initTestSession(() -&gt; TestedClass.main(new String[0]));
     * </pre>
     *
     * @param main {@code Runnable}, invoking the method to be tested
     */
    public static void initTestSession(Runnable main) {
        isTesting = true;
        enforceExit();
        barrier = new CyclicBarrier(2);
        testThread = new Thread(() -> {
            try {
                main.run();
            } catch (ExitException e) {
                if (!e.interrupted && printPolicy > 1) {
                    System.out.println(">>> MISMATCH: " + e.getMessage());
                }
            } catch (Throwable t) {
                try {
                    printInOut("An exception occured: " + t.toString());
                    exitQueue.offer(t, timeOut, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                }
                return;
            }
            try {
                exitQueue.offer(new ExitException(), timeOut, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
            }
        }, "test thread");
        testThread.start();
    }

    /**
     * Utility method for running a test that should be canceled. Using this method prevents an
     * uncaught exception and will cancel only the single test instead of the entire test routine.
     * <p>
     * While running the testing method, the cancelPolicy will be changed to CANCEL_ALWAYS, and
     * reset to the preceding value afterwards.
     * <p>
     * Example:
     *
     * <pre>
     * Terminal.runCancelingTest(() -&gt; testMethod());
     * </pre>
     *
     * @param test the test method to be run
     */
    public static void runCancelingTest(Runnable test) {
        runCancelingTest(test, true);
    }

    /**
     * Utility method for running a test that should be canceled. Using this method prevents an
     * uncaught exception and will cancel only the single test instead of the entire test routine.
     * <p>
     * While running the testing method, the cancelPolicy will (depending on the cancelAtFailure
     * parameter) be changed to CANCEL_ALWAYS or CANCEL_AT_MISMATCH, and reset to the preceding
     * value afterwards.
     *
     * @param test the test method to be run
     * @param cancelAtFailure if false, cancels only at mismatches (default: true)
     * @see #runCancelingTest(Runnable)
     */
    public static void runCancelingTest(Runnable test, boolean cancelAtFailure) {
        runCancelingTest(test, null, cancelAtFailure);
    }

    /**
     * Utility method for running a test that should be canceled. Using this method prevents an
     * uncaught exception and will cancel only the single test instead of the entire test routine.
     * <p>
     * While running the testing method, the cancelPolicy will be changed to CANCEL_ALWAYS, and
     * reset to the preceding value afterwards. If the test cancels, the {@code message} will be
     * printed.
     *
     * @param test the test method to be run
     * @param message printed if the test cancels
     * @see #runCancelingTest(Runnable)
     */
    public static void runCancelingTest(Runnable test, String message) {
        runCancelingTest(test, message, true);
    }

    /**
     * Utility method for running a test that should be canceled. Using this method prevents an
     * uncaught exception and will cancel only the single test instead of the entire test routine.
     * <p>
     * While running the testing method, the cancelPolicy will (depending on the cancelAtFailure
     * parameter) be changed to CANCEL_ALWAYS or CANCEL_AT_MISMATCH, and reset to the preceding
     * value afterwards. If the test cancels, the {@code message} will be printed.
     *
     * @param test the test method to be run
     * @param message printed if the test cancels
     * @param cancelAtFailure if false, cancels only at mismatches (default: true)
     * @see #runCancelingTest(Runnable)
     */
    public static void runCancelingTest(Runnable test, String message, boolean cancelAtFailure) {
        int oldPolicy = cancelPolicy;
        setCancelPolicy(cancelAtFailure ? CANCEL_ALWAYS : CANCEL_AT_MISMATCH);
        try {
            test.run();
        } catch (CancelException e) {
            if (message != null) {
                System.out.println(message);
            }
        } finally {
            setCancelPolicy(oldPolicy);
        }
    }

    /**
     * Defines the input the tested program will receive by the next call to
     * {@code Terminal.readLine()}.
     * <p>
     * The method will block at maximum as long as defined by
     * {@code Terminal.setTimeOut(long millis)}.
     *
     * @param input the input for the Terminal
     * @throws CancelException if the tested program currently accepts no input and the cancel
     *         policy requests a cancel for mismatches
     */
    public static void nextInput(String input) {
        boolean success;
        try {
            success = transferQueue.offer(input, timeOut, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Should never happen at normal use
            e.printStackTrace();
            return;
        }
        if (!success) {
            reportError("expected to be waiting for next input.", true);
        }
    }

    /**
     * Returns the next output printed by the tested program.
     * <p>
     * The method will block at maximum as long as defined by
     * {@code Terminal.setTimeOut(long millis)}.
     *
     * @return output received by Terminal.printLine()
     * @throws CancelException if the tested program currently offers no output and the cancel
     *         policy requests a cancel for mismatches
     */
    public static String nextOutput() {
        return nextOutput("");
    }

    private static String nextOutput(String message) {
        String out;
        try {
            barrier.await(timeOut, TimeUnit.MILLISECONDS);
            out = transferQueue.poll(timeOut, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Should never happen at normal use
            e.printStackTrace();
            return null;
        } catch (TimeoutException | BrokenBarrierException e) {
            barrier = new CyclicBarrier(2);
            out = null;
        }
        if (out == null) {
            reportError(message.isEmpty() ? "additional output expected." : message, true);
        }
        return out;
    }

    /**
     * Tests, whether the next output is equal to the expected output.
     *
     * @param expected the String the output is compared to
     * @return true, if {@code nextOutput().equals(expected)}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertOutput(String expected) {
        return assertOutput("", expected);
    }

    /**
     * Tests, whether the next output is equal to the expected output.
     *
     * @param message printed if the test fails and the printPolicy permits it
     * @param expected the String the output is compared to
     * @return true, if {@code nextOutput().equals(expected)}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertOutput(String message, String expected) {
        return assertString(message, false, false, expected);
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the next output is equal to the expected
     * output.
     *
     * @param input the input invoked with {@code nextInput()}
     * @param expected the String the output is compared to
     * @return true, if {@code nextOutput().equals(expected)}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testOutput(String input, String expected) {
        return testOutput("", input, expected);
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the next output is equal to the expected
     * output.
     *
     * @param message printed if the test fails and the printPolicy permits it
     * @param input the input invoked with {@code nextInput()}
     * @param expected the String the output is compared to
     * @return true, if {@code nextOutput().equals(expected)}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testOutput(String message, String input, String expected) {
        nextInput(input);
        return assertOutput(message, expected);
    }

    /**
     * Tests, whether the prefix of the next output is the expected value.
     *
     * @param expected the String the output is compared to
     * @return true, if {@code nextOutput().startsWith(expected)}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertPrefix(String expected) {
        return assertPrefix("", expected);
    }

    /**
     * Tests, whether the prefix of the next output is the expected value.
     *
     * @param message printed if the test fails and the printPolicy permits it
     * @param expected the String the output is compared to
     * @return true, if {@code nextOutput().startsWith(expected)}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertPrefix(String message, String expected) {
        return assertString(message, true, false, expected);
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the prefix of the next output is the expected
     * value.
     *
     * @param input the input invoked with {@code nextInput()}
     * @param expected the String the output is compared to
     * @return true, if {@code nextOutput().startsWith(expected)}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testPrefix(String input, String expected) {
        return testPrefix("", input, expected);
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the prefix of the next output is the expected
     * value.
     *
     * @param message printed if the test fails and the printPolicy permits it
     * @param input the input invoked with {@code nextInput()}
     * @param expected the String the output is compared to
     * @return true, if {@code nextOutput().startsWith(expected)}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testPrefix(String message, String input, String expected) {
        nextInput(input);
        return assertPrefix(message, expected);
    }

    /**
     * Tests, whether the next outputs match the expected outputs.
     *
     * @param expected the list of Strings the output is compared to
     * @return true, if {@code nextOutput()} matches with each String in {@code expected}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertList(String... expected) {
        return assertList("", false, false, expected);
    }

    /**
     * Tests, whether the next outputs match the expected outputs.
     *
     * @param prefix if true, only the prefix will be compared (default: false)
     * @param randomOrder if true, any possible order of the output will be accepted (default:
     *        false)
     * @param expected the list of Strings the output is compared to
     * @return true, if {@code nextOutput()} matches with each String in {@code expected}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertList(boolean prefix, boolean randomOrder, String... expected) {
        return assertList("", prefix, randomOrder, expected);
    }

    /**
     * Tests, whether the next outputs match the expected outputs.
     *
     * @param message printed if the test fails and the printPolicy permits it
     * @param prefix if true, only the prefix will be compared (default: false)
     * @param randomOrder if true, any possible order of the output will be accepted (default:
     *        false)
     * @param expected the list of Strings the output is compared to
     * @return true, if {@code nextOutput()} matches with each String in {@code expected}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertList(String message, boolean prefix, boolean randomOrder, String... expected) {
        return assertString(message, prefix, randomOrder, expected);
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the next outputs match the expected outputs.
     *
     * @param input the input invoked with {@code nextInput()}
     * @param expected the list of Strings the output is compared to
     * @return true, if {@code nextOutput()} matches with each String in {@code expected}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testList(String input, String... expected) {
        return testList(input, false, false, expected);
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the next outputs match the expected outputs.
     *
     * @param input the input invoked with {@code nextInput()}
     * @param prefix if true, only the prefix will be compared (default: false)
     * @param randomOrder if true, any possible order of the output will be accepted (default:
     *        false)
     * @param expected the list of Strings the output is compared to
     * @return true, if {@code nextOutput()} matches with each String in {@code expected}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testList(String input, boolean prefix, boolean randomOrder, String... expected) {
        return testList("", input, prefix, randomOrder, expected);
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the next outputs match the expected outputs.
     *
     * @param message printed if the test fails and the printPolicy permits it
     * @param input the input invoked with {@code nextInput()}
     * @param prefix if true, only the prefix will be compared (default: false)
     * @param randomOrder if true, any possible order of the output will be accepted (default:
     *        false)
     * @param expected the list of Strings the output is compared to
     * @return true, if {@code nextOutput()} matches with each String in {@code expected}
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testList(String message, String input, boolean prefix, boolean randomOrder,
            String... expected) {
        nextInput(input);
        return assertList(message, prefix, randomOrder, expected);
    }

    /**
     * Tests, whether the program is terminating as next action.
     *
     * @return true, if the program terminated
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertExit() {
        return assertExit("program exit expected.");
    }

    /**
     * Tests, whether the program is terminating as next action.
     *
     * @param message printed if the test fails and the printPolicy permits it
     * @return true, if the program terminated
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean assertExit(String message) {
        if (!testThread.isAlive()) {
            reportError("program already terminated.", true);
            return false;
        }
        Throwable t;
        try {
            t = exitQueue.poll(timeOut, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        if (t == null || t.getClass() != ExitException.class) {
            reportError(message, false);
            return false;
        }
        return true;
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the program is terminating as next action.
     *
     * @param input the input invoked with {@code nextInput()}
     * @return true, if the program terminated
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testExit(String input) {
        return testExit("program exit expected.", input);
    }

    /**
     * Invokes {@code nextInput()} and tests, whether the program is terminating as next action.
     *
     * @param message printed if the test fails and the printPolicy permits it
     * @param input the input invoked with {@code nextInput()}
     * @return true, if the program terminated
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static boolean testExit(String message, String input) {
        nextInput(input);
        return assertExit(message);
    }

    /**
     * Tests whether the specified type of Exception (or, more generally, Throwable) occurs. For
     * this, the class of the Exception is required as parameter.
     * <p>
     * Example:
     *
     * <pre>
     * Terminal.assertException(IllegalArgumentException.class);
     * </pre>
     *
     * @param <T> the type of the expected Expression
     * @param eType the class (or a superclass) of the expected Exception
     * @return true, if the expected Exception occured
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     */
    public static <T extends Throwable> boolean assertException(Class<T> eType) {
        return assertException("", eType);
    }

    /**
     * Tests whether the specified type of Exception (or, more generally, Throwable) occurs. For
     * this, the class of the Exception is required as parameter.
     *
     * @param <T> the type of the expected Expression
     * @param message printed if the test fails and the printPolicy permits it
     * @param eType the class (or a superclass) of the expected Exception
     * @return true, if the expected Exception occured
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     * @see #assertException(Class)
     */
    public static <T extends Throwable> boolean assertException(String message, Class<T> eType) {
        if (!testThread.isAlive()) {
            reportError("program already terminated.", true);
            return false;
        }
        Throwable t;
        try {
            t = exitQueue.poll(timeOut, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        if (t == null || !eType.isInstance(t)) {
            String name = eType.getCanonicalName();
            reportError(message + "\n>>> Expected: " + (name == null ? "Exception" : eType.getCanonicalName()), false);
            return false;
        }
        return true;
    }

    /**
     * Invokes {@code nextInput()} and tests whether the specified type of Exception (or, more
     * generally, Throwable) occurs. For this, the class of the Exception is required as parameter.
     *
     * @param <T> the type of the expected Expression
     * @param input the input invoked with {@code nextInput()}
     * @param eType the class (or a superclass) of the expected Exception
     * @return true, if the expected Exception occured
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     * @see #assertException(Class)
     */
    public static <T extends Throwable> boolean testException(String input, Class<T> eType) {
        return testException("", input, eType);
    }

    /**
     * Invokes {@code nextInput()} and tests whether the specified type of Exception (or, more
     * generally, Throwable) occurs. For this, the class of the Exception is required as parameter.
     *
     * @param <T> the type of the expected Expression
     * @param message printed if the test fails and the printPolicy permits it
     * @param input the input invoked with {@code nextInput()}
     * @param eType the class (or a superclass) of the expected Exception
     * @return true, if the expected Exception occured
     * @throws CancelException if the test fails and the cancel policy requests a cancel
     * @see #assertException(Class)
     */
    public static <T extends Throwable> boolean testException(String message, String input, Class<T> eType) {
        nextInput(input);
        return assertException(message, eType);
    }

    /**
     * Terminates the test (more or less) immediately. For this method working properly, the tested
     * program must not catch RuntimeExceptions (or a supertype) thrown by {@code readLine()} or a
     * print method.
     * <p>
     * Precisely, the tested program will be terminated as soon as it invokes any Terminal method.
     * The method itself returns without delay.
     */
    public static void enforceExit() {
        testThread.interrupt();
    }

    /**
     * Prints the given error-{@code message} with the prefix "{@code Error, }".
     *
     * <p>
     * More specific, this method behaves exactly as if the following code got executed:
     * <blockquote>
     *
     * <pre>
     * Terminal.printLine("Error, " + message);
     * </pre>
     *
     * </blockquote>
     *
     * @param message the error message to be printed
     * @see #printLine(Object)
     */
    public static void printError(final String message) {
        Terminal.printLine("Error, " + message);
    }

    /**
     * Prints the string representation of an {@code Object} and then terminates the line.
     *
     * <p>
     * If the argument is {@code null}, then the string {@code "null"} is printed, otherwise the
     * object's string value {@code obj.toString()} is printed.
     *
     * @param object the {@code Object} to be printed
     * @see String#valueOf(Object)
     */
    public static void printLine(final Object object) {
        if (!isTesting) {
            System.out.println(object);
            return;
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new ExitException();
        }

        String s = String.valueOf(object);
        boolean success;
        try {
            barrier.await(timeOut << 1, TimeUnit.MILLISECONDS);
            printInOut(s);
            success = transferQueue.offer(s, timeOut << 1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new ExitException();
        } catch (TimeoutException | BrokenBarrierException e) {
            barrier = new CyclicBarrier(2);
            printInOut(s);
            success = false;
        }
        if (!success) {
            throw new ExitException(Thread.currentThread().isInterrupted(), "unexpected output.");
        }
    }

    /**
     * Prints an array of characters and then terminates the line.
     *
     * <p>
     * If the argument is {@code null}, then a {@code NullPointerException} is thrown, otherwise the
     * value of {@code
     * new String(charArray)} is printed.
     *
     * @param charArray an array of chars to be printed
     * @see String#valueOf(char[])
     */
    public static void printLine(final char[] charArray) {
        Terminal.printLine(new String(charArray));
    }

    /**
     * Reads a line of text. A line is considered to be terminated by any one of a line feed ('\n'),
     * a carriage return ('\r'), or a carriage return followed immediately by a linefeed.
     *
     * @return a {@code String} containing the contents of the line, not including any
     *         line-termination characters, or {@code null} if the end of the stream has been
     *         reached
     */
    public static String readLine() {
        if (!isTesting) {
            try {
                return IN.readLine();
            } catch (final IOException e) {
                /*
                 * The IOException will not occur during tests executed by the praktomat, therefore
                 * the following RuntimeException does not have to get handled.
                 */
                throw new RuntimeException(e);
            }
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new ExitException();
        }

        String in;
        try {
            in = transferQueue.poll(timeOut << 1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new ExitException();
        }
        if (in == null) {
            throw new ExitException(Thread.currentThread().isInterrupted(), " unexpected readLine() invokation.");
        } else {
            printInOut("> " + in);
        }
        return in;
    }

    /**
     * Reads the file with the specified path and returns its content stored in a {@code String}
     * array. Whereas the first array field contains the file's first line, the second field
     * contains the second line, and so on.
     * <p>
     * <b>Not supported for testing yet.</b>
     *
     * @param path the path of the file to be read
     * @return the content of the file stored in a {@code String} array
     */
    public static String[] readFile(final String path) {
        if (!isTesting) {
            try (final BufferedReader reader = new BufferedReader(new FileReader(path))) {
                return reader.lines().toArray(String[]::new);
            } catch (final IOException e) {
                /*
                 * You can expect that the praktomat exclusively provides valid file-paths.
                 * Therefore there will no IOException occur while reading in files during the
                 * tests, the following RuntimeException does not have to get handled.
                 */
                throw new RuntimeException(e);
            }
        }

        throw new UnsupportedOperationException("Method not supported for testing yet.");
    }
}