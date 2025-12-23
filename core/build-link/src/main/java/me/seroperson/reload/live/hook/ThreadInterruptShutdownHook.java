package me.seroperson.reload.live.hook;

import me.seroperson.reload.live.build.BuildLogger;
import me.seroperson.reload.live.settings.DevServerSettings;

public class ThreadInterruptShutdownHook implements Hook {

  @Override
  public String description() {
    return "Interrupts the main application thread";
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public void hook(Thread th, ClassLoader cl, DevServerSettings settings, BuildLogger logger) {
    th.interrupt();
    logger.debug("Waiting thread to finish");
    try {
      th.join();

      /*var appThreadGroup = th.getThreadGroup();
      logger.debug("Interrupting thread group");
      appThreadGroup.interrupt();
      Thread[] threads = new Thread[appThreadGroup.activeCount()];
      int count = appThreadGroup.enumerate(threads);
      logger.debug("Thread group has " + count + " threads");
      for (int i = 0; i < count; i++) {
        try {
          logger.debug("Interrupting " + threads[i] + " thread");
          threads[i].interrupt();
          threads[i].join();
        } catch (InterruptedException e) {
          logger.debug("Interrupted exception while stopping thread group");
          e.printStackTrace();
        }
      }*/
    } catch (InterruptedException ex) {
      logger.error("Interrupted during join", ex);
    }
  }
}
