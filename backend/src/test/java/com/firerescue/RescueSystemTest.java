package com.firerescue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class RescueSystemTest {

    @Test
    @DisplayName("Verify exact 4-tier Priority Queue comparator")
    public void testPriorityQueueOrdering() {
        Timestamp now = new Timestamp(System.currentTimeMillis());

        // Higher intensity wins
        RescueSystem.Incident i1 = new RescueSystem.Incident("I1", "TownCenter", "Fire", 9, 5, false, 1, "Pending", "None", "", "tok1", now);
        RescueSystem.Incident i2 = new RescueSystem.Incident("I2", "Market", "Fire", 5, 20, true, 2, "Pending", "None", "", "tok2", now);
        assertTrue(RescueSystem.PRIORITY.compare(i1, i2) < 0, "Intensity 9 should beat intensity 5");

        // Same intensity -> Critical infrastructure wins
        RescueSystem.Incident i3 = new RescueSystem.Incident("I3", "Hospital", "Fire", 8, 2, true, 3, "Pending", "None", "", "tok3", now);
        RescueSystem.Incident i4 = new RescueSystem.Incident("I4", "School", "Fire", 8, 100, false, 4, "Pending", "None", "", "tok4", now);
        assertTrue(RescueSystem.PRIORITY.compare(i3, i4) < 0, "Critical incident should beat non-critical when intensities are equal");

        // Same intensity & critical -> Population wins
        RescueSystem.Incident i5 = new RescueSystem.Incident("I5", "ResidentialArea", "Fire", 7, 50, false, 5, "Pending", "None", "", "tok5", now);
        RescueSystem.Incident i6 = new RescueSystem.Incident("I6", "OldTown", "Fire", 7, 10, false, 6, "Pending", "None", "", "tok6", now);
        assertTrue(RescueSystem.PRIORITY.compare(i5, i6) < 0, "Higher population (50 vs 10) should win when intensity and critical are equal");

        // Same intensity, critical, and population -> Earlier arrival order wins (FIFO)
        RescueSystem.Incident i7 = new RescueSystem.Incident("I7", "Warehouse", "Fire", 6, 10, false, 7, "Pending", "None", "", "tok7", now);
        RescueSystem.Incident i8 = new RescueSystem.Incident("I8", "Market", "Fire", 6, 10, false, 8, "Pending", "None", "", "tok8", now);
        assertTrue(RescueSystem.PRIORITY.compare(i7, i8) < 0, "Earlier arrival order (7 vs 8) should win when all other fields match");
    }

    @Test
    @DisplayName("Verify OOP ResponseUnit hierarchy and capability validation")
    public void testUnitCapabilities() {
        RescueSystem.FireEngine fe = new RescueSystem.FireEngine("FE01", "Station1", "Station1", "Industrial Fire");
        assertTrue(fe.canHandle("Fire"));
        assertTrue(fe.canHandle("Industrial Fire"));
        assertFalse(fe.canHandle("Rescue"));
        assertFalse(fe.canHandle("Accident"));

        RescueSystem.RescueVan rv = new RescueSystem.RescueVan("RV01", "Station2", "Station2");
        assertFalse(rv.canHandle("Fire"));
        assertFalse(rv.canHandle("Industrial Fire"));
        assertTrue(rv.canHandle("Rescue"));
        assertTrue(rv.canHandle("Accident"));
    }

    @Test
    @DisplayName("Verify PBKDF2 Password Hashing & Verification")
    public void testPasswordHashing() {
        byte[] salt = RescueSystem.generateSalt();
        String hash = RescueSystem.hashPassword("Admin@123", salt);

        assertNotNull(hash);
        assertFalse(hash.isBlank());

        // Verify correct password produces matching hash with same salt
        String recomputed = RescueSystem.hashPassword("Admin@123", salt);
        assertEquals(hash, recomputed);

        // Verify wrong password does not match
        String wrong = RescueSystem.hashPassword("WrongPass", salt);
        assertNotEquals(hash, wrong);
    }

    @Test
    @DisplayName("Verify token generator format")
    public void testTokenGeneration() {
        String token = RescueSystem.generateToken();
        assertNotNull(token);
        assertTrue(token.startsWith("tr_"), "Token should start with tr_");
        assertEquals(27, token.length(), "Token length should be 3 (tr_) + 24 (12 hex bytes)");
    }
}