package com.example.leaseplatform.service;

import com.example.leaseplatform.domain.Property;
import com.example.leaseplatform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class DemoInventory {
    private final PropertyRepository properties;
    private final DatabaseTime clock;

    public DemoInventory(PropertyRepository properties, DatabaseTime clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void seed(String owner) {
        var now = clock.now();
        for (String name : new String[]{"Harbour Loft", "Parkside Studio", "The Junction Suite"}) {
            properties.save(Property.demo(name, now, owner, false));
        }
    }

    @Transactional
    public UUID trialProperty(String owner) {
        return properties.saveAndFlush(Property.demo("Concurrency trial", clock.now(), owner, true)).getId();
    }
}
