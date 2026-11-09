package local.personaldashboard.mobile;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

public final class UpdateJob extends JobService {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Future<?> task;
    public static void schedule(Context context) {
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler.getPendingJob(8102)!=null) return;
        scheduler.schedule(new JobInfo.Builder(8102,new ComponentName(context,UpdateJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15*60*1000L).setPersisted(true)
            .setBackoffCriteria(60*60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
    public boolean onStartJob(JobParameters parameters) {
        task=executor.submit(()->{
            boolean success=Updates.run(this,false);
            jobFinished(parameters,!success);
        }); return true;
    }
    public boolean onStopJob(JobParameters parameters) {
        if(task!=null) task.cancel(true);
        return true;
    }
    public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
