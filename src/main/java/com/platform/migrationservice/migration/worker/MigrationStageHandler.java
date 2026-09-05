package com.platform.migrationservice.migration.worker;

import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationStage;

/**
 * provider × stage 하나를 처리하는 확장점. live connector·media copier·link resolver가
 * 이 인터페이스로 붙는다. 구현체는 트랜잭션 밖에서 호출되므로 스스로 멱등해야 한다.
 */
public interface MigrationStageHandler {

    MigrationProvider provider();

    MigrationStage stage();

    MigrationStageOutcome handle(MigrationStageWork work);
}
