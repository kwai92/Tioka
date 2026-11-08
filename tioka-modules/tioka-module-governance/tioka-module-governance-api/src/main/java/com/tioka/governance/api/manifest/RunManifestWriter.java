package com.tioka.governance.api.manifest;

/**
 * 运行清单写入端口（ADR-075 / OBS-06）。Turn / Run / Flow Run 开始时调用。
 */
public interface RunManifestWriter {

    /** @return 清单条目 ID */
    String write(RunManifestWriteRequest request);
}
