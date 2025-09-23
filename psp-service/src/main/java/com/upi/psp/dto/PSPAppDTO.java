package com.upi.psp.dto;

import com.upi.psp.model.PSPApp;

public class PSPAppDTO {

    private String pspId;
    private String name;
    private String upiHandle;

    // Constructors
    public PSPAppDTO(String pspId, String name, String upiHandle) {
        this.pspId = pspId;
        this.name = name;
        this.upiHandle = upiHandle;
    }

    // Getters and Setters
    public String getPspId() {
        return pspId;
    }

    public void setPspId(String pspId) {
        this.pspId = pspId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUpiHandle() {
        return upiHandle;
    }

    public void setUpiHandle(String upiHandle) {
        this.upiHandle = upiHandle;
    }

    // Static method to convert from entity to DTO
    public static PSPAppDTO fromEntity(PSPApp pspApp) {
        return new PSPAppDTO(pspApp.getPspId(), pspApp.getName(), pspApp.getUpiHandle());
    }
}
