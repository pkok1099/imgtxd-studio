package com.onlasdan.studio.worker;

import com.onlasdan.studio.worker.IWorkerCallback;
import com.onlasdan.studio.worker.model.JobRequest;
import com.onlasdan.studio.worker.model.JobResult;
import com.onlasdan.studio.worker.model.JobProgress;

interface IWorkerService {
    String submitJob(in JobRequest request);
    boolean cancelJob(String jobId);
    List<JobProgress> getActiveJobs();
    void registerCallback(IWorkerCallback callback);
    void unregisterCallback(IWorkerCallback callback);
    void setAppForegroundState(boolean isForeground);
    void setBackgroundCoreLimit(int maxCores);
}
