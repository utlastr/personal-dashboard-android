package local.personaldashboard.mobile;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

public final class CollectJob extends JobService {
    private Future<?> task;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    public static void schedule(Context context) {
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.schedule(new JobInfo.Builder(8101,new ComponentName(context,CollectJob.class))
            .setPeriodic(15*60*1000L).setPersisted(true).build());
    }
    public boolean onStartJob(JobParameters parameters) {
        task=executor.submit(()->{
            try { Collector.collect(this); SyncClient.sync(this); }
            catch(Exception error) { Collector.prefs(this).edit().putString("error",error.getMessage()).apply(); }
            finally { jobFinished(parameters,false); }
        }); return true;
    }
    public boolean onStopJob(JobParameters parameters) { if(task!=null) task.cancel(true); return true; }
    public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
