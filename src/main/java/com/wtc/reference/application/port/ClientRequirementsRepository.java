package com.wtc.reference.application.port;

import com.wtc.reference.domain.ClientRequirement;
import java.util.List;

/** Port de lecture des exigences de version des applications clientes. */
public interface ClientRequirementsRepository {

    List<ClientRequirement> findAll();
}
