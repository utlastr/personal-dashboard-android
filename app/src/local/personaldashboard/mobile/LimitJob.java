package local.personaldashboard.mobile;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

public final class LimitJob extends JobService {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Future<?> task;
    public static void schedule(Context c) {
        JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler.getPendingJob(8103)==null) scheduler.schedule(new JobInfo.Builder(8103,
            new ComponentName(c,LimitJob.class)).setPeriodic(15*60*1000L).setPersisted(true).build());
    }
    public boolean onStartJob(JobParameters params) {
        task=executor.submit(()->{try { AppLimits.apply(this); } finally { jobFinished(params,false); }});
        return true;
    }
    public boolean onStopJob(JobParameters params) { if(task!=null) task.cancel(true); return true; }
    public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
