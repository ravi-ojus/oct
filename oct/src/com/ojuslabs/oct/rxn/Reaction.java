package com.ojuslabs.oct.rxn;

import java.util.ArrayList;

import com.google.common.collect.Lists;
import com.ojuslabs.oct.common.Constants;
import com.ojuslabs.oct.data.Molecule;

/**
 * Reaction is central to this library. It represents a chemical reaction with
 * <ul>
 * <li>one or more reactants,</li>
 * <li>one or more products,</li>
 * <li>one or more catalysts,</li>
 * <li>one or more reagents and</li>
 * <li>one or more solvents.</li>
 * </ul>
 * <p>
 * In addition, it has a rich variety of characteristics including
 * <ul>
 * <li>yield,</li>
 * <li>allowed temperature range,</li>
 * <li>interfering groups (including potential destructions),</li>
 * <li>reaction conditions and</li>
 * <li>literature references.</li>
 * </ul>
 */
public class Reaction
{
    final int           _reactionNumber; // Reaction number (from the database
                                         // of general reactions) which is
                                         // associated with this reaction
                                         // object.

    ArrayList<Molecule> _reactants;     // Reactant and coreactants.
    ArrayList<Molecule> _products;      // Primary product and byproducts.
    ArrayList<Molecule> _catalysts;     // These do NOT contribute any atoms to
                                         // the
                                         // products.
    ArrayList<Molecule> _reagents;      // These contribute non-carbon atoms.
    ArrayList<Molecule> _solvents;      // These do NOT contribute any atoms to
                                         // the
                                         // products.

    double              _yield;         // Yield of this reaction as a
                                         // percentage.

    public Reaction(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException(
                    String.format("Reaction number has to be a positive integer."));
        }
        _reactionNumber = n;

        _reactants = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_T);
        _products = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_T);
        _catalysts = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_T);
        _reagents = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_T);
        _solvents = Lists.newArrayListWithCapacity(Constants.LIST_SIZE_T);
    }
}
