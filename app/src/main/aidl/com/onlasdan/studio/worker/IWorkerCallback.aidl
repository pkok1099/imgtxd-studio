package com.onlasdan.studio.worker;

import com.onlasdan.studio.worker.model.JobProgress;
import com.onlasdan.studio.worker.model.JobResult;

interface IWorkerCallback {
    void onProgress(in JobProgress progress);
    void onFinished(in JobResult result);
    void onQueueChanged(in List<JobProgress> queue);
}
