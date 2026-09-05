package com.platform.migrationservice.migration.worker;

import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationStage;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** provider × stage → handler 조회. 같은 조합이 둘이면 기동 시점에 실패한다. */
@Component
public class MigrationStageHandlerRegistry {

    /** 등록된 handler가 없는 stage는 조용히 건너뛰지 않고 즉시 실패시킨다. */
    public static final String HANDLER_UNAVAILABLE = "STAGE_HANDLER_UNAVAILABLE";

    private final Map<Key, MigrationStageHandler> handlers = new HashMap<>();

    public MigrationStageHandlerRegistry(List<MigrationStageHandler> registered) {
        for (MigrationStageHandler handler : registered) {
            Key key = new Key(handler.provider(), handler.stage());
            MigrationStageHandler previous = handlers.put(key, handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate migration stage handler for " + key.provider() + "/" + key.stage());
            }
        }
    }

    public Optional<MigrationStageHandler> find(MigrationProvider provider, MigrationStage stage) {
        return Optional.ofNullable(handlers.get(new Key(provider, stage)));
    }

    public MigrationStageHandler require(MigrationProvider provider, MigrationStage stage) {
        return find(provider, stage)
                .orElseThrow(() -> MigrationStageException.permanent(HANDLER_UNAVAILABLE));
    }

    private record Key(MigrationProvider provider, MigrationStage stage) {
    }
}
