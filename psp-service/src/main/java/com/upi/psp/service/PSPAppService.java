package com.upi.psp.service;

import com.upi.psp.model.PSPApp;
import com.upi.psp.repo.PSPAppRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class PSPAppService {

    private final PSPAppRepository appRepository;

    // Constructor injection of the repository
    public PSPAppService(PSPAppRepository appRepository) {
        this.appRepository = appRepository;
    }

    // 1. Create or Update PSPApp
    public PSPApp save(PSPApp app) {
        return appRepository.save(app);  // The save method works for both creating and updating.
    }

    // 2. Get PSPApp by UPI handle (Read)
    public Optional<PSPApp> getByHandle(String handle) {
        return appRepository.findByUpiHandle(handle);  // This will return an Optional<PSPApp>
    }

    // 3. Get PSPApp by ID (Read)
    public Optional<PSPApp> getById(String pspId) {
        return appRepository.findById(pspId);  // This will return an Optional<PSPApp>
    }

    // 4. Delete PSPApp by ID
    public void deleteById(String pspId) {
        appRepository.deleteById(pspId);  // Deletes by the primary key (ID)
    }

    // 5. Check if PSPApp exists by ID
    public boolean existsById(String pspId) {
        return appRepository.existsById(pspId);  // Checks if the PSPApp exists in the DB
    }

    // 6. Get All PSPApps (Optional - for listing all entries)
    public Iterable<PSPApp> getAll() {
        return appRepository.findAll();  // Returns all PSPApp records
    }
}
