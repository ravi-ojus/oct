/**
 * Copyright (c) 2012-2013 Ojus Software Labs Private Limited.
 * 
 * All rights reserved. Please see the files README.md, LICENSE and COPYRIGHT
 * for details.
 */

package com.ojuslabs.oct.lib;

import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.ojuslabs.oct.common.Constants;
import com.ojuslabs.oct.core.Atom;
import com.ojuslabs.oct.core.Bond;
import com.ojuslabs.oct.core.Molecule;
import com.ojuslabs.oct.core.Ring;

/**
 * The default ring detector.
 */
public final class RingDetector implements IRingDetector {

    // The molecule to analyse.
    private Molecule             _mol;

    // Internal data holders for analysis and detection.
    private List<Atom>           _atoms;
    private List<List<Atom>>     _nbrs;
    private Deque<List<Atom>>    _candidates;

    private List<Ring>           _rings;
    private List<List<Ring>>     _ringSystems;
    private List<BitSet>         _ringSystemBonds;

    // The validators to employ before approving a candidate path as a ring.
    private List<IRingValidator> _initialValidators;
    // The validators to employ while pruning detected rings.
    private List<IRingValidator> _pruningValidators;

    /**
     * Registers a set of validators that each has to approve the candidate path
     * as a ring, with this detector.
     */
    public RingDetector() {
        _initialValidators = Lists.newArrayList();

        // A junction atom cannot have _all_ of its neighbours in any one ring!
        _initialValidators.add(new IRingValidator() {
            @Override
            public boolean validate(Molecule mol, List<Atom> atoms,
                    List<List<Atom>> nbrs, List<Atom> path) {
                // System.out.println("-- Validating path: "
                // + Joiner.on(", ").join(path));

                for (Atom a : path) {
                    int idx = atoms.indexOf(a);
                    List<Atom> anbrs = nbrs.get(idx);
                    if (anbrs.size() < 3) {
                        continue;
                    }

                    boolean allFound = true;
                    for (Atom n : anbrs) {
                        if (-1 == path.indexOf(n)) {
                            allFound = false;
                            break;
                        }
                    }

                    // If all neighbours exist in this path, then it is a
                    // spurious outer shell path, not a genuine ring.
                    if (allFound) {
                        return false;
                    }
                }

                return true;
            }
        });
    }

    /**
     * <b>N.B.</b> <b><i>This method answers the live internal list of atoms,
     * only for efficiency reasons. Modification of this list leads to undefined
     * results.</i></b>
     * 
     * @return The internal list of atoms in this molecule. During ring
     *         detection, this list contains only non-terminal atoms.
     */
    List<Atom> atoms() {
        return _atoms;
    }

    /**
     * <b>N.B.</b> <b><i>This method answers the live internal list of atom
     * neighbours, only for efficiency reasons. Modification of this list leads
     * to undefined results.</i></b>
     * 
     * @return The internal list of neighbour lists of atoms in this molecule.
     *         During ring detection, this list of lists contains those only for
     *         non-terminal atoms.
     */
    List<List<Atom>> neighbours() {
        return _nbrs;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.ojuslabs.oct.lib.IRingDetector#initialise(com.ojuslabs.oct.core.Molecule
     * )
     */
    @Override
    public void initialise(Molecule mol) {
        if (null == mol) {
            throw new IllegalArgumentException("Null molecule given.");
        }

        _mol = mol;
        _atoms = Lists.newArrayListWithCapacity(_mol.numberOfAtoms());
        _nbrs = Lists.newArrayListWithCapacity(_mol.numberOfAtoms());
        _candidates = Queues.newArrayDeque();

        _rings = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_M);
        _ringSystems = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_S);
        _ringSystemBonds = Lists
                .newArrayListWithCapacity(Constants.LIST_SIZE_S);

        // Initialise the atoms and their neighbours.
        for (Atom a : _mol.atoms()) {
            _atoms.add(a);

            List<Atom> nbrs = Lists
                    .newArrayListWithCapacity(Constants.LIST_SIZE_S);
            _nbrs.add(nbrs);
            for (Bond b : a.bonds()) {
                Atom nbr = b.otherAtom(a.id());
                nbrs.add(nbr);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.ojuslabs.oct.lib.IRingDetector#detectRings()
     */
    @Override
    public void detectRings() {
        // First, remove all terminal chains.
        pruneTerminalChains();

        // At this point, if all atoms have exactly two neighbours, there can be
        // only one ring.
        if (noAtomsWithGT2Bonds()) {
            detectTheOnlyRing();
            return;
        }

        detectMultipleRings();
        detectRingSystems();
        pruneRings();
    }

    /**
     * A terminal atom is one that has only one neighbour. Removal of a terminal
     * atom may make its neighbour a terminal one, which then has to be removed
     * in turn. In effect, all such terminal chains have to be removed.
     */
    void pruneTerminalChains() {
        boolean running = true;

        outer:
        while (running) {
            running = false;

            for (int i = 0; i < _atoms.size(); i++) {
                if (1 == _nbrs.get(i).size()) {
                    pruneTerminalAtom(i);
                    running = true; // An atom has been pruned.
                    continue outer; // We should now check for cascades.
                }
            }
        }
    }

    /**
     * @param i
     *            Index of the atom already identified as a terminal atom.
     */
    void pruneTerminalAtom(int i) {
        Atom a = _atoms.get(i);
        // System.out.println("-- Pruning atom: " + a.inputId());

        // Remove references to this atom from the neighbour lists of its own
        // neighbours.
        Atom nbr = _nbrs.get(i).get(0);
        int nbrIdx = _atoms.indexOf(nbr);
        Iterator<Atom> nit = _nbrs.get(nbrIdx).iterator();
        while (nit.hasNext()) {
            Atom nbrNbr = nit.next();
            if (a == nbrNbr) {
                nit.remove();
                break;
            }
        }

        // Now, we remove this atom itself.
        _atoms.remove(i);
        _nbrs.remove(i);
    }

    /**
     * @return {@code true} if there are no atoms having more than two
     *         neighbours; {@code false} otherwise.
     */
    boolean noAtomsWithGT2Bonds() {
        for (List<Atom> l : _nbrs) {
            if (l.size() > 2) {
                return false;
            }
        }

        return true;
    }

    /**
     * Forms a ring out of the remaining atoms, and adds it to the molecule.
     */
    void detectTheOnlyRing() {
        List<Atom> path = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_S);
        Atom start = _atoms.get(0);
        path.add(start);

        int i = 0;
        Atom prev = start;
        Atom curr = start;
        Atom next = null;
        while (true) {
            i = _atoms.indexOf(curr);
            List<Atom> nbrs = _nbrs.get(i);
            next = nbrs.get(0);
            if (next == prev) { // We don't want to turn around!
                next = nbrs.get(1);
            }
            if (next == start) { // We have completed the ring.
                break;
            }

            path.add(next);
            prev = curr;
            curr = next;
        }

        makeRingFrom(path);
    }

    /**
     * Detects the multitude of rings in the given molecule.
     */
    void detectMultipleRings() {
        List<Atom> path = Lists
                .newArrayListWithCapacity(Constants.LIST_SIZE_S);

        boolean found = false;
        for (Atom a : _atoms) {
            if (a.isJunction()) {
                continue;
            }
            found = true;

            path.add(a);
            break;
        }
        if (!found) {
            path.add(_atoms.get(0));
        }
        _candidates.add(path);

        while (!_candidates.isEmpty()) {
            tryPath(_candidates.remove());
        }
    }

    /**
     * @param path
     *            A list of atoms potentially forming a part of a candidate
     *            ring.
     */
    void tryPath(List<Atom> path) {
        // System.out.println(Joiner.on(", ").join(path));

        int size = path.size();
        Atom start = path.get(0);
        Atom curr = path.get(size - 1);
        Atom prev = (size > 1) ? path.get(size - 2) : curr;

        int i = _atoms.indexOf(curr);
        for (Atom next : _nbrs.get(i)) {
            if (next == prev) { // We don't want to traverse backwards!
                continue;
            }
            if (next == start) { // We have a candidate.
                if (validatePath(path)) {
                    makeRingFrom(path);
                }
                continue;
            }

            // We could encounter a previously encountered atom. In that case,
            // we have a potential ring.
            int idx = path.indexOf(next);
            if (-1 != idx) {
                List<Atom> tpath = path.subList(idx, path.size());
                if (validatePath(tpath)) {
                    makeRingFrom(tpath);
                }
                continue;
            }

            List<Atom> newPath = Lists.newArrayList(path);
            newPath.add(next);
            _candidates.add(newPath);
        }
    }

    /**
     * @param path
     *            A list of atoms potentially forming a candidate ring.
     * @return {@code true} if the path passes all tests to become a valid ring;
     *         {@code false} otherwise.
     */
    boolean validatePath(List<Atom> path) {
        // A 3-membered path that comes this far is a valid ring!
        if (3 == path.size()) {
            return true;
        }

        // Otherwise, we run the path through all the registered validators.
        for (IRingValidator v : _initialValidators) {
            if (!v.validate(_mol, _atoms, _nbrs, path)) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param path
     *            A list of atoms validated as forming a ring.
     */
    void makeRingFrom(List<Atom> path) {
        Ring r = new Ring(_mol);
        for (Atom a : path) {
            r.addAtom(a);
        }
        r.complete();
        if (!_rings.contains(r)) {
            _rings.add(r);
        }
    }

    /**
     * Groups the detected rings into ring systems based on fusion.
     */
    void detectRingSystems() {
        int rsid = 0;
        List<Ring> rs = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_S);
        _ringSystems.add(rs);
        _ringSystemBonds.add(_rings.get(0).bondBitSet());

        for (Ring r : _rings) {
            BitSet rbs = r.bondBitSet();

            boolean found = false;
            for (int i = 0; i < _ringSystems.size(); i++) {
                BitSet rsbs = (BitSet) _ringSystemBonds.get(i).clone();
                rsbs.and(rbs);
                if (rsbs.cardinality() > 0) {
                    r.setRingSystemId(rsid);
                    _ringSystems.get(i).add(r);
                    _ringSystemBonds.get(i).or(rbs);
                    found = true;
                    break;
                }
            }

            if (!found) {
                rsid++;
                List<Ring> nrs = Lists
                        .newArrayListWithCapacity(Constants.LIST_SIZE_S);
                r.setRingSystemId(rsid);
                nrs.add(r);
                _ringSystems.add(nrs);
                _ringSystemBonds.add(r.bondBitSet());
            }
        }
    }

    /**
     * Removes spurious rings from the set of detected ones.
     */
    void pruneRings() {
        // We sort each ring system ascending on ring size.
        Comparator<Ring> c = new Comparator<Ring>() {
            @Override
            public int compare(Ring r1, Ring r2) {
                int s1 = r1.size();
                int s2 = r2.size();
                return (s1 < s2) ? -1 : (s1 == s2) ? 0 : 1;
            }
        };

        for (int i = 0; i < _ringSystems.size(); i++) {
            List<Ring> rs = _ringSystems.get(i);
            Collections.sort(rs, c);
            BitSet rsbs = _ringSystemBonds.get(i);

            // We find the smallest collection of classes of rings of the same
            // size that covers the ring system exactly.
            int prevSize = rs.get(0).size();
            int currSize = 0;
            BitSet bs = new BitSet(_mol.numberOfBonds());
            int lastIncludedRingIdx = -1;
            boolean found = false;
            for (int j = 0; j < rs.size(); j++) {
                Ring r = rs.get(j);
                currSize = r.size();
                if (currSize == prevSize) {
                    bs.or(r.bondBitSet());
                    lastIncludedRingIdx = j;
                }
                else {
                    BitSet tbs = (BitSet) bs.clone();
                    tbs.xor(rsbs);
                    if (0 == tbs.cardinality()) {
                        found = true;
                        break;
                    }

                    prevSize = currSize;
                }
            }

            // We remove all such larger rings, which can not be expressed as a
            // union of exactly any two of the already included rings, from the
            // ring system.
            if (found) {
                boolean running = true;
                while (running) {
                    running = false;

                    for (int j = lastIncludedRingIdx + 1; j < rs.size(); j++) {
                        Ring tr = rs.get(j);
                        if (!isUnionOfSomeTwoRingsUpto(lastIncludedRingIdx, rs,
                                tr)) {
                            _rings.remove(tr);
                            rs.remove(j);
                            running = true;
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * @param rs
     *            The ring system being pruned.
     * @param lastIncludedRingIdx
     *            Marks the last ring to consider in the ring system.
     * @param r
     *            The ring to test if it can be formed as a union of exactly two
     *            rings.
     * @return {@code true} if the ring can be formed as a union of exactly two
     *         included rings; {@code false} otherwise.
     */
    boolean isUnionOfSomeTwoRingsUpto(int lastIncludedRingIdx, List<Ring> rs,
            Ring r) {
        BitSet tbs = r.bondBitSet();

        for (int i = 0; i < lastIncludedRingIdx; i++) {
            BitSet bs1 = rs.get(i).bondBitSet();
            for (int j = i + 1; j <= lastIncludedRingIdx; j++) {
                BitSet bs2 = rs.get(j).bondBitSet();
                // BitSet bs3 = (BitSet) bs2.clone();
                bs2.or(bs1); // Union of ring1 and ring2.
                bs2.and(tbs); // Intersection of the above union and test ring.
                // bs3.and(bs1); // Intersection of ring1 and ring2.
                // bs2.andNot(bs3); // Set minus.
                bs2.xor(tbs);

                // The remainder matches the test ring.
                if (0 == bs2.cardinality()) {
                    return true;
                }
            }
        }

        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.ojuslabs.oct.lib.IRingDetector#rings()
     */
    @Override
    public List<Ring> rings() {
        return ImmutableList.copyOf(_rings);
    }
}