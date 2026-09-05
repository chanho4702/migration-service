package com.platform.migrationservice.migration.repository;

import com.platform.migrationservice.migration.model.MigrationSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationSourceRepository extends JpaRepository<MigrationSource, Long> {
}
