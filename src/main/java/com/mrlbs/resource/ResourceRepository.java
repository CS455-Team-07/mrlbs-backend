package com.mrlbs.resource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ResourceRepository extends JpaRepository<LabResource, UUID> {
}
