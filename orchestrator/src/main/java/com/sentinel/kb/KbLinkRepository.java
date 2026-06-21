package com.sentinel.kb;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KbLinkRepository extends JpaRepository<KbLink, UUID> {

    List<KbLink> findByFromService(String fromService);

    List<KbLink> findByToService(String toService);
}
