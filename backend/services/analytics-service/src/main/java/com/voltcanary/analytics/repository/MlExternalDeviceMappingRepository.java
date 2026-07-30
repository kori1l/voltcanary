package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlExternalDeviceMapping;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlExternalDeviceMappingRepository extends JpaRepository<MlExternalDeviceMapping, UUID> {
    List<MlExternalDeviceMapping> findByImportedFileIdOrderByExternalDeviceKey(UUID importedFileId);
}
