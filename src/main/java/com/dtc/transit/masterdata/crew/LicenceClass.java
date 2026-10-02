package com.dtc.transit.masterdata.crew;

/**
 * Driving licence class.
 *
 * <p>A heavy passenger vehicle needs HPMV; a lighter licence cannot legally cover it, which Phase 9
 * checks when matching crew to a block's vehicle class.
 */
public enum LicenceClass {
    LMV,
    HMV,
    HPMV
}
