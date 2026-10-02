package com.dtc.transit.scheduling.engine.model;

import java.util.List;

/**
 * One bus's working day, as a finished chain of legs.
 *
 * <p>Immutable. The builder works on a mutable draft and seals it into this, so nothing downstream can
 * quietly extend a block that has already been costed and validated.
 *
 * @param vehicleClass the class the block requires, which is the strictest requirement among its trips
 * @param serviceKm revenue kilometres, which is what utilisation is measured against
 * @param deadKm non-revenue kilometres, which is what the engine is trying to minimise
 */
public record Block(
        int blockNo,
        String vehicleClass,
        int pullOutSec,
        int pullInSec,
        double serviceKm,
        double deadKm,
        List<BlockEvent> events) {

    public Block {
        events = List.copyOf(events);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("block " + blockNo + " has no events");
        }
        if (pullInSec <= pullOutSec) {
            throw new IllegalArgumentException("block " + blockNo + " ends at or before it starts");
        }
    }

    public int durationSec() {
        return pullInSec - pullOutSec;
    }

    public double totalKm() {
        return serviceKm + deadKm;
    }

    /** The trips this block covers, in order. */
    public List<Long> tripIds() {
        return events.stream()
                .filter(event -> event.type() == BlockEventType.TRIP)
                .map(BlockEvent::tripId)
                .toList();
    }

    public List<BlockEvent> reliefOpportunities() {
        return events.stream().filter(BlockEvent::reliefOpportunity).toList();
    }

    /** A copy with its events replaced, used when the relief finder marks opportunities. */
    public Block withEvents(List<BlockEvent> replacement) {
        return new Block(blockNo, vehicleClass, pullOutSec, pullInSec, serviceKm, deadKm, replacement);
    }

    public Block withBlockNo(int newBlockNo) {
        return new Block(newBlockNo, vehicleClass, pullOutSec, pullInSec, serviceKm, deadKm, events);
    }
}
