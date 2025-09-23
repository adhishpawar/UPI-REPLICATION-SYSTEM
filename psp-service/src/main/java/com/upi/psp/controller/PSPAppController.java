package com.upi.psp.controller;

import com.upi.psp.model.PSPApp;
import com.upi.psp.service.PSPAppService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/psp-apps")
public class PSPAppController {

    private final PSPAppService pspAppService;

    @Autowired
    public PSPAppController(PSPAppService pspAppService) {
        this.pspAppService = pspAppService;
    }

    // Create or Update PSPApp
    @PostMapping
    public ResponseEntity<PSPApp> createOrUpdatePspApp(@RequestBody PSPApp pspApp) {
        PSPApp savedPspApp = pspAppService.save(pspApp);
        return new ResponseEntity<>(savedPspApp, HttpStatus.CREATED);
    }

    // Get PSPApp by UPI handle
    @GetMapping("/handle/{handle}")
    public ResponseEntity<PSPApp> getPspAppByHandle(@PathVariable String handle) {
        Optional<PSPApp> pspApp = pspAppService.getByHandle(handle);
        return pspApp.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Get PSPApp by ID
    @GetMapping("/{pspId}")
    public ResponseEntity<PSPApp> getPspAppById(@PathVariable String pspId) {
        Optional<PSPApp> pspApp = pspAppService.getById(pspId);
        return pspApp.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Delete PSPApp by ID
    @DeleteMapping("/{pspId}")
    public ResponseEntity<Void> deletePspApp(@PathVariable String pspId) {
        if (pspAppService.existsById(pspId)) {
            pspAppService.deleteById(pspId);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // Get All PSPApps
    @GetMapping
    public ResponseEntity<Iterable<PSPApp>> getAllPspApps() {
        return ResponseEntity.ok(pspAppService.getAll());
    }
}
