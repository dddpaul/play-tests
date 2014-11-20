package play.test;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.WebDriverRunner;
import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.PatternLayout;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.JUnit4;
import org.junit.runners.model.InitializationError;
import play.Logger;
import play.Play;
import play.i18n.Lang;
import play.server.Server;
import play.test.coverage.ActionCoveragePlugin;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static com.google.common.io.Resources.toByteArray;
import static java.lang.System.currentTimeMillis;
import static org.openqa.selenium.net.PortProber.findFreePort;

public class PlayTestsRunner extends Runner implements Filterable {
  private Class testClass;
  private JUnit4 jUnit4;
  private Filter filter;

  public PlayTestsRunner(Class testClass) throws InitializationError {
    this.testClass = testClass;
    jUnit4 = new JUnit4(testClass);
  }

  private static String getPlayId() {
    String playId = System.getProperty("play.id", "test");
    if(! (playId.startsWith("test-") && playId.length() >= 6)) {
      playId = "test";
    }
    return playId;
  }

  @Override
  public Description getDescription() {
    return jUnit4.getDescription();
  }

  @Override
  public void run(final RunNotifier notifier) {
    boolean firstRun = startPlayIfNeeded();
    loadTestClassWithPlayClassloader();
    Lang.clear();

    if (Play.mode.isProd()) addTimesLogger();

    if (firstRun) warmupApplication();

    jUnit4.run(notifier);
  }
  
  private static void log(String message) {
    System.out.println("-------------------------------\n" +
        new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date()) + " " + message + "\n" +
        "-------------------------------\n");
  }

  private void addTimesLogger() {
    ActionCoveragePlugin.timeLogger = new UITimeLogger();
    WebDriverRunner.addListener(ActionCoveragePlugin.timeLogger);
  }

  private void warmupApplication() {
    try {
      toByteArray(new URL(Configuration.baseUrl));
    } catch (IOException e) {
      System.err.println("Failed to load URL " + Configuration.baseUrl + ":");
      e.printStackTrace();
    }
  }

  private void loadTestClassWithPlayClassloader() {
    Class precompiledTestClass = Play.classloader.loadApplicationClass(testClass.getName());
    if (precompiledTestClass == null) {
      System.err.println("Warning: test classes are not precompiled. May cause problems if using JPA in tests.");
      return;
    }

    try {
      testClass = precompiledTestClass;
      jUnit4 = new JUnit4(testClass);
      if (filter != null) {
        jUnit4.filter(filter);
      }
    }
    catch (InitializationError initializationError) {
      throw new RuntimeException(initializationError);
    }
    catch (NoTestsRemainException itCannotHappen) {
      throw new RuntimeException(itCannotHappen);
    }
  }

  protected boolean startPlayIfNeeded() {
    synchronized (Play.class) {
      if (isPlayStartNeeded() && !Play.started) {
        TimeZone.setDefault(TimeZone.getTimeZone(System.getProperty("selenide.play.timeZone", "Asia/Krasnoyarsk")));

        long start = currentTimeMillis();
        
        Play.usePrecompiled = "true".equalsIgnoreCase(System.getProperty("precompiled", "false"));
        Play.init(new File("."), getPlayId());
        makeUniqueTempPath();
        Play.javaPath.add(Play.getVirtualFile("test-ui"));
        if (!Play.started) {
          Play.start();
        }

        runPlayKillerThread();

        int port = findFreePort();
        new Server(new String[]{"--http.port=" + port});

        Configuration.baseUrl = "http://localhost:" + port;
        Play.configuration.setProperty("application.baseUrl", Configuration.baseUrl);

        duplicateLogsOfEveryTestProcessToSeparateFile();

        long end = currentTimeMillis();
        log("Started Play! application in " + (end - start) + " ms.");
        
        return true;
      }
    }
    return false;
  }

  private boolean isPlayStartNeeded() {
    return !"false".equalsIgnoreCase(System.getProperty("selenide.play.start", "true"));
  }

  private void runPlayKillerThread() {
    Thread thread = new Thread(new PlayKillerThread(), "Play killer thread");
    thread.setDaemon(true);
    thread.start();
  }

  void makeUniqueTempPath() {
    File tmp = new File(new File("tmp", Play.configuration.getProperty("application.name")), Play.id);
    tmp = new File(tmp, ManagementFactory.getRuntimeMXBean().getName());

    if (!tmp.exists()) tmp.mkdirs();
    Play.configuration.setProperty("play.tmp", tmp.getAbsolutePath());
    System.setProperty("java.io.tmpdir", tmp.getAbsolutePath());
    Play.tmpDir = tmp;
  }

  private void duplicateLogsOfEveryTestProcessToSeparateFile() {
    Logger.log4j = org.apache.log4j.Logger.getLogger("play");
    String logFileName = "test-result/" + ManagementFactory.getRuntimeMXBean().getName() + ".log";
    org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();
    try {
      Appender testLog = new FileAppender(new PatternLayout("%d{DATE} %-5p ~ %m%n"), Play.getFile(logFileName).getAbsolutePath(), false);
      rootLogger.addAppender(testLog);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void filter(Filter filter) throws NoTestsRemainException {
    this.filter = filter;
    jUnit4.filter(filter);
  }

  private static Long timeToKillPlay;
  private static String requesterInfo;

  public static void scheduleKillPlay(String requester, long killAfterNMilliseconds) {
    timeToKillPlay = currentTimeMillis() + killAfterNMilliseconds;
    requesterInfo = "Scheduled Play! kill by " + requester  + "  to " + new Date(timeToKillPlay);
  }

  private static class PlayKillerThread implements Runnable {
    @Override public void run() {
      while (!Thread.interrupted()) {
        if (timeToKillPlay != null && timeToKillPlay < currentTimeMillis() && Play.started) {
          log("Stopping play! application \nRequested by: " + requesterInfo);
          Play.stop();
          log("Stopped play! application.");
          break;
        }
        else {
          try {
            Thread.sleep(3000);
          }
          catch (InterruptedException e) {
            break;
          }
        }
      }
    }
  }
}
