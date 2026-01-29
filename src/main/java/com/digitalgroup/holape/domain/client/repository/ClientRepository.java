package com.digitalgroup.holape.domain.client.repository;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

    List<Client> findByStatus(Status status);

    Optional<Client> findByWhatsappNumber(String whatsappNumber);

    Optional<Client> findByWhatsappBusinessId(String businessId);

    @Query("SELECT c FROM Client c WHERE c.status = :status ORDER BY c.name")
    List<Client> findAllActiveClients(@Param("status") Status status);

    @Query("SELECT c FROM Client c LEFT JOIN FETCH c.clientSettings WHERE c.id = :id")
    Optional<Client> findByIdWithSettings(@Param("id") Long id);

    @Query("SELECT c FROM Client c WHERE c.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE")
    List<Client> findByActiveTrue();
}
