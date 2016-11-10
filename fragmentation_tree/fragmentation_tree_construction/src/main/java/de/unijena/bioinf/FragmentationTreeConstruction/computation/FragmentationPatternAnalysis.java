/*
 *  This file is part of the SIRIUS library for analyzing MS and MS/MS data
 *
 *  Copyright (C) 2013-2015 Kai Dührkop
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with SIRIUS.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.unijena.bioinf.FragmentationTreeConstruction.computation;


import de.unijena.bioinf.ChemistryBase.algorithm.Called;
import de.unijena.bioinf.ChemistryBase.algorithm.ParameterHelper;
import de.unijena.bioinf.ChemistryBase.algorithm.Parameterized;
import de.unijena.bioinf.ChemistryBase.algorithm.Scored;
import de.unijena.bioinf.ChemistryBase.chem.*;
import de.unijena.bioinf.ChemistryBase.chem.utils.scoring.Hetero2CarbonScorer;
import de.unijena.bioinf.ChemistryBase.data.DataDocument;
import de.unijena.bioinf.ChemistryBase.math.ExponentialDistribution;
import de.unijena.bioinf.ChemistryBase.math.LogNormalDistribution;
import de.unijena.bioinf.ChemistryBase.ms.*;
import de.unijena.bioinf.ChemistryBase.ms.ft.*;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleMutableSpectrum;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleSpectrum;
import de.unijena.bioinf.ChemistryBase.ms.utils.Spectrums;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.filtering.*;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.graph.GraphBuilder;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.graph.GraphReduction;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.graph.SimpleReduction;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.graph.SubFormulaGraphBuilder;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.inputValidator.InputValidator;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.inputValidator.MissingValueValidator;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.inputValidator.Warning;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.merging.HighIntensityMerger;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.merging.Merger;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.merging.PeakMerger;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.recalibration.RecalibrationMethod;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.scoring.*;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.tree.TreeBuilder;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.tree.maximumColorfulSubtree.TreeBuilderFactory;
import de.unijena.bioinf.FragmentationTreeConstruction.model.*;
import de.unijena.bioinf.MassDecomposer.Chemistry.DecomposerCache;
import de.unijena.bioinf.MassDecomposer.Chemistry.MassToFormulaDecomposer;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.function.Identity;
import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;

import java.util.*;

/**
 * FragmentationPatternAnalysis contains the pipeline for computing fragmentation trees
 * This is done using the following steps:
 * 1. Validate input and correct/fillin missing values
 * 2. Preprocess input (add baseline, noise filters and so on)
 * 3. Merge peaks within the same spectra, compute relative intensitities
 * 4. Merge peaks from different spectra, compute a flat peak list
 * 5. Postprocessing (delete peaks with low intensitities and so on)
 * 6. Detect parent peak
 * 7. Decompose each peak
 * 8. Postprocesing
 * 9. Score each peak and each pair of peaks
 *
 * Steps 1-9 are bundled within the method FragmentationPatternAnalysis#preprocessing
 *
 * Now for each Molecular formula candidate:
 * 10. Compute Fragmentation Graph
 * 11. Score losses and vertices in the graph
 * 12. Compute Fragmentation tree
 * 13. Recalibrate tree
 * 14. Might repeat all steps with recalibration function
 * 15. Postprocess tree
 *
 * Steps 10-15 are bundled within the method FragmentationPatternAnalysis#computeTree
 *
 * You can run each step individually. However, you have to run step 1. first to get a ProcessedInput object.
 * This object contains the Ms2Experiment input and stores all intermediate values during the computation.
 *
 * Some (or, honestly, most) steps rely on certain properties and intermediate values computed in previous steps.
 * So you have to be very careful when running a step separately. The recommended way is to run the whole pipeline.
 *
 *
 */
public class FragmentationPatternAnalysis implements Parameterized, Cloneable {
    private List<InputValidator> inputValidators;
    private Warning validatorWarning;
    private boolean repairInput;
    private NormalizationType normalizationType;
    private PeakMerger peakMerger;
    private DecomposerCache decomposers;
    private List<DecompositionScorer<?>> decompositionScorers;
    private List<DecompositionScorer<?>> rootScorers;
    private List<LossScorer> lossScorers;
    private List<PeakPairScorer> peakPairScorers;
    private List<PeakScorer> fragmentPeakScorers;
    private GraphBuilder graphBuilder;
    private List<Preprocessor> preprocessors;
    private List<PostProcessor> postProcessors;
    private TreeBuilder treeBuilder;
    private MutableMeasurementProfile defaultProfile;
    private RecalibrationMethod recalibrationMethod;
    private GraphReduction reduction;
    private IsotopePatternInMs2Scorer isoInMs2Scorer;

    private static ParameterHelper parameterHelper = ParameterHelper.getParameterHelper();

    /**
     * Step 1-9: Preprocessing
     */
    public ProcessedInput preprocessing(Ms2Experiment experiment) {
        return preprocessing(experiment, new MutableMeasurementProfile());
    }

    /**
     * Step 1-9: Preprocessing
     */
    public ProcessedInput preprocessing(Ms2Experiment experiment, FormulaConstraints constraints) {
        final MutableMeasurementProfile p = new MutableMeasurementProfile(getDefaultProfile());
        p.setFormulaConstraints(constraints);
        return preprocessing(experiment, p);
    }

    /**
     * Step 1-9: Preprocessing
     * Allows to use different values for decomposition
     */
    public ProcessedInput preprocessing(Ms2Experiment experiment, MeasurementProfile profile) {
        ProcessedInput input = performValidation(experiment);
        input.setMeasurementProfile(MutableMeasurementProfile.merge(input.getMeasurementProfile(), profile));
        return performPeakScoring(performDecomposition(performParentPeakDetection(performPeakMerging(performNormalization(performPreprocessing(input))))));
    }

    public ProcessedInput preprocessing(Ms2Experiment experiment, MeasurementProfile profile, RecalibrationFunction function) {
        ProcessedInput input = performValidation(experiment);
        input.setMeasurementProfile(MutableMeasurementProfile.merge(input.getMeasurementProfile(), profile));
        input = performPeakMerging(performNormalization(performPreprocessing(input)));
        for (ProcessedPeak peak : input.getMergedPeaks()) {
            peak.setOriginalMz(peak.getMz());
            peak.setMz(function.apply(peak.getMz()));
        }
        return performPeakScoring(performDecomposition(performParentPeakDetection(input)));
    }

    /**
     * Step 1. Validate Input
     * Correct and fillin missing values
     * @param originalExperiment
     * @return A ProcessedInput object wrapping the original input
     */
    public ProcessedInput performValidation(Ms2Experiment originalExperiment) {
        // first of all: insert default profile if no profile is given

        MutableMs2Experiment input = new MutableMs2Experiment(originalExperiment);
        Ms2Experiment exp = input;
        for (InputValidator validator : inputValidators) {
            exp = validator.validate(exp, validatorWarning, repairInput);
        }

        final ProcessedInput pinput =  new ProcessedInput(new MutableMs2Experiment(exp), originalExperiment, new MutableMeasurementProfile(defaultProfile));

        if (originalExperiment.getMolecularFormula()!=null) {
            pinput.getMeasurementProfile().setFormulaConstraints(pinput.getMeasurementProfile().getFormulaConstraints().getExtendedConstraints(FormulaConstraints.allSubsetsOf(originalExperiment.getMolecularFormula())));
        }

        return pinput;
    }

    /**
     * Step 2. Preprocessing
     * Apply all preprocessing routines to the input
     * @param experiment
     * @return
     */
    public ProcessedInput performPreprocessing(ProcessedInput experiment) {
        MutableMs2Experiment exp = experiment.getExperimentInformation();
        for (Preprocessor proc : preprocessors) {
            exp = proc.process(exp, experiment.getMeasurementProfile());
        }
        experiment.setExperimentInformation(exp);
        return experiment;
    }

    /**
     * Step 3. Normalizing
     * Merge all peaks within a single spectrum
     * Return a list of peaks (from all spectra) with relative intensities
     */
    public ProcessedInput performNormalization(ProcessedInput input) {
        final Ms2Experiment experiment = input.getExperimentInformation();
        final double parentMass = experiment.getIonMass();
        final ArrayList<ProcessedPeak> peaklist = new ArrayList<ProcessedPeak>(100);
        final Deviation mergeWindow = getDefaultProfile().getAllowedMassDeviation().divide(2d);
        final Ionization ion = experiment.getPrecursorIonType().getIonization();
        double globalMaxIntensity = 0d;
        for (Ms2Spectrum s : experiment.getMs2Spectra()) {
            // merge peaks: iterate them from highest to lowest intensity and remove peaks which
            // are in the mass range of a high intensive peak
            final MutableSpectrum<Peak> sortedByIntensity = new SimpleMutableSpectrum(s);
            Spectrums.sortSpectrumByDescendingIntensity(sortedByIntensity);
            // simple spectra are always ordered by mass
            final SimpleSpectrum sortedByMass = new SimpleSpectrum(s);
            final BitSet deletedPeaks = new BitSet(s.size());
            for (int i = 0; i < s.size(); ++i) {
                // get index of peak in mass-ordered spectrum
                final double mz = sortedByIntensity.getMzAt(i);
                final int index = Spectrums.binarySearch(sortedByMass, mz);
                assert index >= 0;
                if (deletedPeaks.get(index)) continue; // peak is already deleted
                // delete all peaks within the mass range
                for (int j = index - 1; j >= 0 && mergeWindow.inErrorWindow(mz, sortedByMass.getMzAt(j)); --j)
                    deletedPeaks.set(j, true);
                for (int j = index + 1; j < s.size() && mergeWindow.inErrorWindow(mz, sortedByMass.getMzAt(j)); ++j)
                    deletedPeaks.set(j, true);
            }
            final int offset = peaklist.size();
            // add all remaining peaks to the peaklist
            for (int i = 0; i < s.size(); ++i) {
                if (!deletedPeaks.get(i)) {
                    final ProcessedPeak propeak = new ProcessedPeak(new MS2Peak(s, sortedByMass.getMzAt(i), sortedByMass.getIntensityAt(i)));
                    propeak.setIon(ion);
                    peaklist.add(propeak);

                }
            }
            // now performNormalization spectrum. Ignore peaks near to the parent peak
            final double lowerbound = parentMass - 0.1d;
            double scale = 0d;
            for (int i = offset; i < peaklist.size() && peaklist.get(i).getMz() < lowerbound; ++i) {
                scale = Math.max(scale, peaklist.get(i).getIntensity());
            }
            if (scale==0) scale = peaklist.get(0).getIntensity(); // happens for spectra with only one peak
            // now set local relative intensities
            for (int i = offset; i < peaklist.size(); ++i) {
                final ProcessedPeak peak = peaklist.get(i);
                peak.setLocalRelativeIntensity(peak.getIntensity() / scale);
            }
            // and adjust global relative intensity
            globalMaxIntensity = Math.max(globalMaxIntensity, scale);
        }
        // now calculate global normalized intensities
        for (ProcessedPeak peak : peaklist) {
            peak.setGlobalRelativeIntensity(peak.getIntensity() / globalMaxIntensity);
            peak.setRelativeIntensity(normalizationType == NormalizationType.GLOBAL ? peak.getGlobalRelativeIntensity() : peak.getLocalRelativeIntensity());
        }
        // finished!
        input.setMergedPeaks(peaklist);

        // postprocess
        postProcess(PostProcessor.Stage.AFTER_NORMALIZING, input);
        return input;
    }
    /**
     *
     * Step 4. Merging
     * a set of peaks are merged if:
     * - they are from different spectra
     * - they are in the same mass range
     */
    public ProcessedInput performPeakMerging(ProcessedInput input) {
        Ms2Experiment experiment = input.getExperimentInformation();
        List<ProcessedPeak> peaklists = input.getMergedPeaks();
        final ArrayList<ProcessedPeak> mergedPeaks = new ArrayList<ProcessedPeak>(peaklists.size());
        peakMerger.mergePeaks(peaklists, experiment, getDefaultProfile().getAllowedMassDeviation().multiply(2), new Merger() {
            @Override
            public ProcessedPeak merge(List<ProcessedPeak> peaks, int index, double newMz) {
                final ProcessedPeak newPeak = peaks.get(index);
                // sum up global intensities, take maximum of local intensities
                double local = 0d, global = 0d, relative = 0d;
                for (ProcessedPeak p : peaks) {
                    local = Math.max(local, p.getLocalRelativeIntensity());
                    global += p.getGlobalRelativeIntensity();
                    relative += p.getRelativeIntensity();
                }
                newPeak.setMz(newMz);
                newPeak.setLocalRelativeIntensity(local);
                newPeak.setGlobalRelativeIntensity(global);
                newPeak.setRelativeIntensity(relative);
                final MS2Peak[] originalPeaks = new MS2Peak[peaks.size()];
                for (int i = 0; i < peaks.size(); ++i) originalPeaks[i] = peaks.get(i).getOriginalPeaks().get(0);
                newPeak.setOriginalPeaks(Arrays.asList(originalPeaks));
                mergedPeaks.add(newPeak);
                return newPeak;
            }
        });
        {
            // DEBUGGING
            Collections.sort(mergedPeaks);
        }
        input.setMergedPeaks(mergedPeaks);
        postProcess(PostProcessor.Stage.AFTER_MERGING, input);
        return input;
    }

    /**
     * Step 5. Parent Peak Detection
     * Scans the spectrum for the parent peak, delete all peaks with higher masses than the parent peak and
     * (noise) peaks which are near the parent peak. If there is no parent peak found, a synthetic one is created.
     * After cleaning, the processedPeaks list should contain the parent peak as last peak in the list. Furthermore,
     * is is guaranteed, that the heaviest peak in the list is always the parent peak.
     */
    public ProcessedInput performParentPeakDetection(ProcessedInput input) {
        final List<ProcessedPeak> processedPeaks = input.getMergedPeaks();
        final Ms2Experiment experiment = input.getExperimentInformation();
        // and sort the resulting peaklist by mass
        Collections.sort(processedPeaks, new ProcessedPeak.MassComparator());

        double parentmass = experiment.getIonMass();

        Peak ms1parent = null;

        // if ms1 spectra are available: use the parentpeak from them
        if (!experiment.getMs1Spectra().isEmpty()) {
            Spectrum<Peak> spec = experiment.getMergedMs1Spectrum();
            if (spec == null) spec = experiment.getMs1Spectra().get(0);
            final Deviation parentDeviation = getDefaultProfile().getAllowedMassDeviation();
            final int i = Spectrums.mostIntensivePeakWithin(Spectrums.getMassOrderedSpectrum(spec), parentmass, parentDeviation);
            if (i >= 0) {
                ms1parent = spec.getPeakAt(i);
            }
        }
        if (ms1parent!=null) {
            parentmass = ms1parent.getMass();
        }


        // now search the parent peak. If it is not contained in the spectrum: create one!
        // delete all peaks behind the parent, such that the parent is the heaviest peak in the spectrum
        // Now we can access the parent peak by peaklist[peaklist.size-1]
        final Deviation parentDeviation = getDefaultProfile().getAllowedMassDeviation();
        for (int i = processedPeaks.size() - 1; i >= 0; --i) {
            if (!parentDeviation.inErrorWindow(parentmass, processedPeaks.get(i).getMz())) {
                if (processedPeaks.get(i).getMz() < parentmass) {
                    // parent peak is not contained. Create a synthetic one
                    addSyntheticParent(experiment, processedPeaks, parentmass);
                    break;
                } else processedPeaks.remove(i);
            } else break;
        }
        if (processedPeaks.isEmpty()) {
            addSyntheticParent(experiment, processedPeaks, parentmass);
        }

        // set parent peak mass to ms1 parent mass
        if (ms1parent!=null) {
            processedPeaks.get(processedPeaks.size() - 1).setMz(ms1parent.getMass());
            processedPeaks.get(processedPeaks.size() - 1).setOriginalMz(ms1parent.getMass());
        }

        assert parentDeviation.inErrorWindow(parentmass, processedPeaks.get(processedPeaks.size() - 1).getMz()) : "heaviest peak is parent peak";
        // the heaviest fragment that is possible is M - H
        // everything which is heavier is noise
        final double threshold = parentmass + getDefaultProfile().getAllowedMassDeviation().absoluteFor(parentmass) - PeriodicTable.getInstance().getByName("H").getMass();
        final ProcessedPeak parentPeak = processedPeaks.get(processedPeaks.size() - 1);

        // if ms1 peak present, use his mass and intensity as parent peak
        /*
        if (ms1parent != null) {
            parentPeak.setMz(ms1parent.getMass());
            parentPeak.setOriginalMz(ms1parent.getMass());
        }
        */

        // delete all peaks between parentmass-H and parentmass except the parent peak itself
        for (int i = processedPeaks.size() - 2; i >= 0; --i) {
            if (processedPeaks.get(i).getMz() <= threshold) break;
            processedPeaks.set(processedPeaks.size() - 2, parentPeak);
            processedPeaks.remove(processedPeaks.size() - 1);
        }

        input.setParentPeak(parentPeak);

        return input;
    }

    /**
     * Step 6: Decomposition
     * Decompose each peak as well as the parent peak
     */
    public ProcessedInput performDecomposition(ProcessedInput input) {
        final FormulaConstraints constraints = input.getMeasurementProfile().getFormulaConstraints();
        final Ms2Experiment experiment = input.getExperimentInformation();
        final Deviation parentDeviation = input.getMeasurementProfile().getAllowedMassDeviation();
        // sort again...
        final ArrayList<ProcessedPeak> processedPeaks = new ArrayList<ProcessedPeak>(input.getMergedPeaks());
        Collections.sort(processedPeaks, new ProcessedPeak.MassComparator());
        final ProcessedPeak parentPeak = processedPeaks.get(processedPeaks.size() - 1);
        // decompose peaks
        final PeakAnnotation<DecompositionList> decompositionList = input.getOrCreatePeakAnnotation(DecompositionList.class);
        final MassToFormulaDecomposer decomposer = decomposers.getDecomposer(constraints.getChemicalAlphabet());
        final Ionization ion = experiment.getPrecursorIonType().getIonization();
        final Deviation fragmentDeviation = input.getMeasurementProfile().getAllowedMassDeviation();
        final List<MolecularFormula> pmds = decomposer.decomposeToFormulas(experiment.getPrecursorIonType().subtractIonAndAdduct(parentPeak.getOriginalMz()), parentDeviation, constraints);
        // add adduct to molecular formula of the ion - because the adduct might get lost during fragmentation
        {
            final MolecularFormula adduct = experiment.getPrecursorIonType().getAdduct();
            final ListIterator<MolecularFormula> iter = pmds.listIterator();
            while (iter.hasNext()) {
                final MolecularFormula f = iter.next();
                iter.set(f.add(adduct));
            }
        }
        decompositionList.set(parentPeak, DecompositionList.fromFormulas(pmds));
        int j = 0;
        for (ProcessedPeak peak : processedPeaks.subList(0, processedPeaks.size() - 1)) {
            peak.setIndex(j++);
            final double mass = peak.getUnmodifiedMass();
            if (mass > 0) {
                decompositionList.set(peak, DecompositionList.fromFormulas(decomposer.decomposeToFormulas(mass, fragmentDeviation, constraints)));
            } else decompositionList.set(peak, new DecompositionList(new ArrayList<Scored<MolecularFormula>>(0)));
        }
        parentPeak.setIndex(processedPeaks.size() - 1);
        assert parentPeak == processedPeaks.get(processedPeaks.size() - 1);
        // important: for each two peaks which are within 2*massrange:
        //  => make decomposition list disjoint
        final Deviation window = fragmentDeviation.multiply(2);
        for (int i = 1; i < processedPeaks.size() - 1; ++i) {
            if (window.inErrorWindow(processedPeaks.get(i).getMz(), processedPeaks.get(i - 1).getMz())) {
                final HashSet<MolecularFormula> right = new HashSet<MolecularFormula>(decompositionList.get(processedPeaks.get(i)).getFormulas());
                final ArrayList<MolecularFormula> left = new ArrayList<MolecularFormula>(decompositionList.get(processedPeaks.get(i-1)).getFormulas());
                final double leftMass = ion.subtractFromMass(processedPeaks.get(i - 1).getMass());
                final double rightMass = ion.subtractFromMass(processedPeaks.get(i).getMass());
                final Iterator<MolecularFormula> leftIter = left.iterator();
                while (leftIter.hasNext()) {
                    final MolecularFormula leftFormula = leftIter.next();
                    if (right.contains(leftFormula)) {
                        if (Math.abs(leftFormula.getMass() - leftMass) < Math.abs(leftFormula.getMass() - rightMass)) {
                            right.remove(leftFormula);
                        } else {
                            leftIter.remove();
                        }
                    }
                }
                decompositionList.set(processedPeaks.get(i - 1), DecompositionList.fromFormulas(left));
                decompositionList.set(processedPeaks.get(i), DecompositionList.fromFormulas(right));
            }
        }

        return postProcess(PostProcessor.Stage.AFTER_DECOMPOSING, input);
    }

    /**
     * Step 7: Peak Scoring
     * Scores each peak. Expects a decomposition list
     */
    public ProcessedInput performPeakScoring(ProcessedInput input) {
        final List<ProcessedPeak> processedPeaks = input.getMergedPeaks();
        final ProcessedPeak parentPeak = input.getParentPeak();
        final int n = processedPeaks.size();
        input.getOrCreateAnnotation(Scoring.class).initializeScoring(n);
        // score peak pairs
        final double[][] peakPairScores = input.getAnnotationOrThrow(Scoring.class).getPeakPairScores();
        for (PeakPairScorer scorer : peakPairScorers) {
            scorer.score(processedPeaks, input, peakPairScores);
        }
        // score fragment peaks
        final double[] peakScores = input.getAnnotationOrThrow(Scoring.class).getPeakScores();
        for (PeakScorer scorer : fragmentPeakScorers) {
            scorer.score(processedPeaks, input, peakScores);
        }

        final PeakAnnotation<DecompositionList> decomp = input.getPeakAnnotationOrThrow(DecompositionList.class);

        // dont score parent peak
        peakScores[peakScores.length - 1] = 0d;


        // score peaks
        {
            final ArrayList<Object> preparations = new ArrayList<Object>(decompositionScorers.size());
            for (DecompositionScorer<?> scorer : decompositionScorers) preparations.add(scorer.prepare(input));
            for (int i = 0; i < processedPeaks.size() - 1; ++i) {
                final DecompositionList decomps = decomp.get(processedPeaks.get(i));
                final ArrayList<Scored<MolecularFormula>> scored = new ArrayList<Scored<MolecularFormula>>(decomps.getDecompositions().size());
                for (MolecularFormula f : decomps.getFormulas()) {
                    double score = 0d;
                    int k = 0;
                    for (DecompositionScorer<?> scorer : decompositionScorers) {
                        score += ((DecompositionScorer<Object>) scorer).score(f, processedPeaks.get(i), input, preparations.get(k++));
                    }
                    scored.add(new Scored<MolecularFormula>(f, score));
                }
                decomp.set(processedPeaks.get(i), new DecompositionList(scored));
            }
        }
        // same with root
        {
            final ArrayList<Object> preparations = new ArrayList<Object>(rootScorers.size());
            for (DecompositionScorer<?> scorer : rootScorers) preparations.add(scorer.prepare(input));
            final ArrayList<Scored<MolecularFormula>> scored = new ArrayList<Scored<MolecularFormula>>(decomp.get(parentPeak).getDecompositions());
            for (int j=0; j < scored.size(); ++j) {
                double score = 0d;
                int k = 0;
                final MolecularFormula f = scored.get(j).getCandidate();
                for (DecompositionScorer<?> scorer : rootScorers) {
                    score += ((DecompositionScorer<Object>) scorer).score(f, input.getParentPeak(), input, preparations.get(k++));
                }
                scored.set(j, new Scored<MolecularFormula>(f, score));

            }
            Collections.sort(scored, Collections.reverseOrder());
            decomp.set(parentPeak, new DecompositionList(scored));
            input.addAnnotation(DecompositionList.class, decomp.get(parentPeak));
        }
        // set peak indizes
        for (int i = 0; i < processedPeaks.size(); ++i) processedPeaks.get(i).setIndex(i);

        return input;
    }

/*
    public ProcessedInput preprocessingWithRecalibration(Ms2Experiment experiment, RecalibrationMethod.Recalibration recalibration) {
        final ProcessedInput input = preprocessWithoutDecomposing(experiment);
        final UnivariateFunction f = recalibration.recalibrationFunction();
        for (ProcessedPeak peak : input.getMergedPeaks()) {
            //peak.setOriginalMz(peak.getMz());
            peak.setMz(f.value(peak.getOriginalMz()));
        }
        // decompose and score all peaks
        return decomposeAndScore(input.getExperimentInformation(), experiment, input.getMergedPeaks());
    }
*/
    /**
     *
     */
    public FragmentationPatternAnalysis() {
        this.decomposers = new DecomposerCache();
        setInitial();
    }

    public static <G, D, L> FragmentationPatternAnalysis loadFromProfile(DataDocument<G, D, L> document, G value) {
        final ParameterHelper helper = ParameterHelper.getParameterHelper();
        final D dict = document.getDictionary(value);
        if (!document.hasKeyInDictionary(dict, "FragmentationPatternAnalysis"))
            throw new IllegalArgumentException("No field 'FragmentationPatternAnalysis' in profile");
        final FragmentationPatternAnalysis analyzer = (FragmentationPatternAnalysis) helper.unwrap(document,
                document.getFromDictionary(dict, "FragmentationPatternAnalysis"));
        if (document.hasKeyInDictionary(dict, "profile")) {
            final MeasurementProfile prof = ((MeasurementProfile) helper.unwrap(document, document.getFromDictionary(dict, "profile")));
            if (analyzer.defaultProfile == null) analyzer.defaultProfile = new MutableMeasurementProfile(prof);
            else
                analyzer.defaultProfile = new MutableMeasurementProfile(MutableMeasurementProfile.merge(prof, analyzer.defaultProfile));
        }

        analyzer.initialize();

        return analyzer;
    }

    /**
     * Construct a new FragmentationPatternAnaylsis with default scorers
     */
    public void makeToOldSiriusAnalyzer() {
        // peak pair scorers
        final RelativeLossSizeScorer lossSize = new RelativeLossSizeScorer();
        final List<PeakPairScorer> peakPairScorers = new ArrayList<PeakPairScorer>();
        peakPairScorers.add(new CollisionEnergyEdgeScorer(0.1, 0.8));
        peakPairScorers.add(lossSize);

        // loss scorers
        final List<LossScorer> lossScorers = new ArrayList<LossScorer>();
        lossScorers.add(FreeRadicalEdgeScorer.getRadicalScorerWithDefaultSet());
        lossScorers.add(new DBELossScorer());
        lossScorers.add(new PureCarbonNitrogenLossScorer());
        //lossScorers.add(new ChemicalPriorEdgeScorer(new Hetero2CarbonScorer(new NormalDistribution(0.5886335d, 0.5550574d)), 0d, 0d));
        //lossScorers.add(new StrangeElementScorer());
        final CommonLossEdgeScorer alesscorer = new CommonLossEdgeScorer();
        alesscorer.setRecombinator(new CommonLossEdgeScorer.LegacyOldSiriusRecombinator());

        final double GAMMA = 2d;

        for (String s : CommonLossEdgeScorer.ales_list) {
            alesscorer.addCommonLoss(MolecularFormula.parse(s), GAMMA);
        }

        alesscorer.addImplausibleLosses(Math.log(0.25));

        lossScorers.add(new ChemicalPriorEdgeScorer(new Hetero2CarbonScorer(Hetero2CarbonScorer.getHeteroToCarbonDistributionFromKEGG()), 0d, 0d));

        lossScorers.add(alesscorer);

        // peak scorers
        final List<PeakScorer> peakScorers = new ArrayList<PeakScorer>();
        peakScorers.add(new PeakIsNoiseScorer(ExponentialDistribution.getMedianEstimator()));
        peakScorers.add(new TreeSizeScorer(0d));

        // root scorers
        final List<DecompositionScorer<?>> rootScorers = new ArrayList<DecompositionScorer<?>>();
        rootScorers.add(new ChemicalPriorScorer(new Hetero2CarbonScorer(Hetero2CarbonScorer.getHeteroToCarbonDistributionFromKEGG()), 0d, 0d));
        rootScorers.add(new MassDeviationVertexScorer());

        // fragment scorers
        final List<DecompositionScorer<?>> fragmentScorers = new ArrayList<DecompositionScorer<?>>();
        fragmentScorers.add(new MassDeviationVertexScorer());
        //fragmentScorers.add(CommonFragmentsScore.getLearnedCommonFragmentScorer());

        // setup
        setLossScorers(lossScorers);
        setRootScorers(rootScorers);
        setDecompositionScorers(fragmentScorers);
        setFragmentPeakScorers(peakScorers);
        setPeakPairScorers(peakPairScorers);

        setPeakMerger(new HighIntensityMerger(0.01d));
        getPostProcessors().add(new NoiseThresholdFilter(0.005d));
        getPreprocessors().add(new NormalizeToSumPreprocessor());

        getPostProcessors().add(new LimitNumberOfPeaksFilter(40));

        //analysis.setTreeBuilder(new DPTreeBuilder(15));
        setTreeBuilder(TreeBuilderFactory.getInstance().getTreeBuilder(TreeBuilderFactory.DefaultBuilder.GUROBI));

        getDefaultProfile().setMedianNoiseIntensity(ExponentialDistribution.fromLambda(0.4d).getMedian());
    }
    public static FragmentationPatternAnalysis oldSiriusAnalyzer() {
        final FragmentationPatternAnalysis analysis = new FragmentationPatternAnalysis();

        // peak pair scorers
        final RelativeLossSizeScorer lossSize = new RelativeLossSizeScorer();
        final List<PeakPairScorer> peakPairScorers = new ArrayList<PeakPairScorer>();
        peakPairScorers.add(new CollisionEnergyEdgeScorer(0.1, 0.8));
        peakPairScorers.add(lossSize);

        // loss scorers
        final List<LossScorer> lossScorers = new ArrayList<LossScorer>();
        lossScorers.add(FreeRadicalEdgeScorer.getRadicalScorerWithDefaultSet());
        lossScorers.add(new DBELossScorer());
        lossScorers.add(new PureCarbonNitrogenLossScorer());
        //lossScorers.add(new ChemicalPriorEdgeScorer(new Hetero2CarbonScorer(new NormalDistribution(0.5886335d, 0.5550574d)), 0d, 0d));
        //lossScorers.add(new StrangeElementScorer());
        final CommonLossEdgeScorer alesscorer = new CommonLossEdgeScorer();
        alesscorer.setRecombinator(new CommonLossEdgeScorer.LegacyOldSiriusRecombinator());

        final double GAMMA = 2d;

        for (String s : CommonLossEdgeScorer.ales_list) {
            alesscorer.addCommonLoss(MolecularFormula.parse(s), GAMMA);
        }

        alesscorer.addImplausibleLosses(Math.log(0.25));

        lossScorers.add(new ChemicalPriorEdgeScorer(new Hetero2CarbonScorer(Hetero2CarbonScorer.getHeteroToCarbonDistributionFromKEGG()), 0d, 0d));

        lossScorers.add(alesscorer);

        // peak scorers
        final List<PeakScorer> peakScorers = new ArrayList<PeakScorer>();
        peakScorers.add(new PeakIsNoiseScorer(ExponentialDistribution.getMedianEstimator()));
        peakScorers.add(new TreeSizeScorer(0d));

        // root scorers
        final List<DecompositionScorer<?>> rootScorers = new ArrayList<DecompositionScorer<?>>();
        rootScorers.add(new ChemicalPriorScorer(new Hetero2CarbonScorer(Hetero2CarbonScorer.getHeteroToCarbonDistributionFromKEGG()), 0d, 0d));
        rootScorers.add(new MassDeviationVertexScorer());

        // fragment scorers
        final List<DecompositionScorer<?>> fragmentScorers = new ArrayList<DecompositionScorer<?>>();
        fragmentScorers.add(new MassDeviationVertexScorer());
        //fragmentScorers.add(CommonFragmentsScore.getLearnedCommonFragmentScorer());

        // setup
        analysis.setLossScorers(lossScorers);
        analysis.setRootScorers(rootScorers);
        analysis.setDecompositionScorers(fragmentScorers);
        analysis.setFragmentPeakScorers(peakScorers);
        analysis.setPeakPairScorers(peakPairScorers);

        analysis.setPeakMerger(new HighIntensityMerger(0.01d));
        analysis.getPostProcessors().add(new NoiseThresholdFilter(0.005d));
        analysis.getPreprocessors().add(new NormalizeToSumPreprocessor());

        analysis.getPostProcessors().add(new LimitNumberOfPeaksFilter(40));

        //analysis.setTreeBuilder(new DPTreeBuilder(15));
        analysis.setTreeBuilder(TreeBuilderFactory.getInstance().getTreeBuilder(TreeBuilderFactory.DefaultBuilder.GUROBI));

        final MutableMeasurementProfile profile = new MutableMeasurementProfile();

        profile.setAllowedMassDeviation(new Deviation(10, 0.002d));
        profile.setStandardMassDifferenceDeviation(new Deviation(2.5d));
        profile.setStandardMs2MassDeviation(new Deviation(10d, 0.002d));
        profile.setStandardMs1MassDeviation(new Deviation(11d / 4d));
        profile.setFormulaConstraints(new FormulaConstraints());
        profile.setMedianNoiseIntensity(ExponentialDistribution.fromLambda(0.4d).getMedian());
        profile.setIntensityDeviation(0.02);
        analysis.setDefaultProfile(profile);

        analysis.initialize();

        return analysis;
    }

    /**
     * Construct a new FragmentationPatternAnaylsis with default scorers
     */
    public static FragmentationPatternAnalysis defaultAnalyzer() {
        final FragmentationPatternAnalysis analysis = new FragmentationPatternAnalysis();

        // peak pair scorers
        final LossSizeScorer lossSize = new LossSizeScorer(new LogNormalDistribution(4d, 1d), -5d);/*LossSizeScorer.LEARNED_DISTRIBUTION, LossSizeScorer.LEARNED_NORMALIZATION*/
        final List<PeakPairScorer> peakPairScorers = new ArrayList<PeakPairScorer>();
        peakPairScorers.add(new CollisionEnergyEdgeScorer(0.1, 0.8));
        peakPairScorers.add(lossSize);

        // loss scorers
        final List<LossScorer> lossScorers = new ArrayList<LossScorer>();
        lossScorers.add(FreeRadicalEdgeScorer.getRadicalScorerWithDefaultSet());
        lossScorers.add(new DBELossScorer());
        lossScorers.add(new PureCarbonNitrogenLossScorer());
        //lossScorers.add(new StrangeElementScorer());
        lossScorers.add(CommonLossEdgeScorer.getLossSizeCompensationForExpertList(lossSize, 0.75d).addImplausibleLosses(Math.log(0.01)));

        // peak scorers
        final List<PeakScorer> peakScorers = new ArrayList<PeakScorer>();
        peakScorers.add(new PeakIsNoiseScorer());
        peakScorers.add(new TreeSizeScorer(0d));

        // root scorers
        final List<DecompositionScorer<?>> rootScorers = new ArrayList<DecompositionScorer<?>>();
        rootScorers.add(new ChemicalPriorScorer());
        rootScorers.add(new MassDeviationVertexScorer());

        // fragment scorers
        final List<DecompositionScorer<?>> fragmentScorers = new ArrayList<DecompositionScorer<?>>();
        fragmentScorers.add(new MassDeviationVertexScorer());
        fragmentScorers.add(new CommonFragmentsScore(new HashMap<MolecularFormula, Double>()));

        // setup
        analysis.setLossScorers(lossScorers);
        analysis.setRootScorers(rootScorers);
        analysis.setDecompositionScorers(fragmentScorers);
        analysis.setFragmentPeakScorers(peakScorers);
        analysis.setPeakPairScorers(peakPairScorers);

        analysis.setPeakMerger(new HighIntensityMerger(0.01d));
        analysis.getPostProcessors().add(new NoiseThresholdFilter(0.005d));
        analysis.getPreprocessors().add(new NormalizeToSumPreprocessor());


        final TreeBuilder solver = TreeBuilderFactory.getInstance().getTreeBuilder();
        analysis.setTreeBuilder(solver);

        final MutableMeasurementProfile profile = new MutableMeasurementProfile();
        profile.setAllowedMassDeviation(new Deviation(10));
        profile.setStandardMassDifferenceDeviation(new Deviation(7));
        profile.setStandardMs2MassDeviation(new Deviation(10));
        profile.setStandardMassDifferenceDeviation(new Deviation(5));
        profile.setFormulaConstraints(new FormulaConstraints());
        profile.setMedianNoiseIntensity(0.02);
        profile.setIntensityDeviation(0.02);
        analysis.setDefaultProfile(profile);


        analysis.initialize();

        return analysis;
    }

    private void initialize() {
        for (Object o : this.inputValidators) {
            initialize(o);
        }
        initialize(this.peakMerger);
        for (Object o : this.decompositionScorers) {
            initialize(o);
        }
        for (Object o : this.rootScorers) {
            initialize(o);
        }
        for (Object o : this.lossScorers) {
            initialize(o);
        }
        for (Object o : this.peakPairScorers) {
            initialize(o);
        }
        for (Object o : this.fragmentPeakScorers) {
            initialize(o);
        }
        initialize(graphBuilder);
        for (Object o : this.preprocessors) {
            initialize(o);
        }
        for (Object o : this.postProcessors) {
            initialize(o);
        }
        initialize(treeBuilder);
        initialize(recalibrationMethod);
        initialize(reduction);
        initialize(isoInMs2Scorer);
    }
    private void initialize(Object o)  {
        if (o==null) return;
        if (o.getClass().isAssignableFrom(Initializable.class)) {
            ((Initializable)o).initialize(this);
        }
    }



    /**
     * Helper function to change the parameters of a specific scorer
     * <code>
     * analyzer.getByClassName(MassDeviationScorer.class, analyzer.getDecompositionScorers).setMassPenalty(4d);
     * </code>
     *
     * @param klass
     * @param list
     * @param <S>
     * @param <T>
     * @return
     */
    public static <S, T extends S> T getByClassName(Class<T> klass, List<S> list) {
        for (S elem : list) if (elem.getClass().equals(klass)) return (T) elem;
        return null;
    }

    public static <S, T extends S> T getOrCreateByClassName(Class<T> klass, List<S> list) {
        for (S elem : list) if (elem.getClass().equals(klass)) return (T) elem;
        try {
            final T obj = klass.newInstance();
            list.add(obj);
            return obj;
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public <G, D, L> void writeToProfile(DataDocument<G, D, L> document, G value) {
        final ParameterHelper helper = ParameterHelper.getParameterHelper();
        final D dict = document.getDictionary(value);
        final D fpa = document.newDictionary();
        exportParameters(helper, document, fpa);
        document.addToDictionary(fpa, "$name", helper.toClassName(getClass()));
        document.addDictionaryToDictionary(dict, "FragmentationPatternAnalysis", fpa);
        if (document.hasKeyInDictionary(dict, "profile")) {
            final MeasurementProfile otherProfile = (MeasurementProfile) helper.unwrap(document, document.getFromDictionary(dict, "profile"));
            if (!otherProfile.equals(defaultProfile)) {
                if (defaultProfile != null) {
                    final D profDict = document.newDictionary();
                    defaultProfile.exportParameters(helper, document, profDict);
                    document.addDictionaryToDictionary(fpa, "default", profDict);
                }
            }
        } else if (defaultProfile != null) {
            final D profDict = document.newDictionary();
            defaultProfile.exportParameters(helper, document, profDict);
            document.addDictionaryToDictionary(dict, "profile", profDict);
        }
    }

    /**
     * Remove all scorers and set analyzer to clean state
     */
    public void setInitial() {
        this.inputValidators = new ArrayList<InputValidator>();
        inputValidators.add(new MissingValueValidator());
        this.validatorWarning = new Warning.Noop();
        this.normalizationType = NormalizationType.GLOBAL;
        this.peakMerger = new HighIntensityMerger();
        this.repairInput = true;
        this.decompositionScorers = new ArrayList<DecompositionScorer<?>>();
        this.preprocessors = new ArrayList<Preprocessor>();
        this.postProcessors = new ArrayList<PostProcessor>();
        this.rootScorers = new ArrayList<DecompositionScorer<?>>();
        this.peakPairScorers = new ArrayList<PeakPairScorer>();
        this.fragmentPeakScorers = new ArrayList<PeakScorer>();
        this.graphBuilder = new SubFormulaGraphBuilder();
        this.lossScorers = new ArrayList<LossScorer>();
        this.defaultProfile = new MutableMeasurementProfile();

        this.reduction = new SimpleReduction();

        final TreeBuilder solver = TreeBuilderFactory.getInstance().getTreeBuilder();
        setTreeBuilder(solver);

    }

    /**
     * Compute a single fragmentation tree
     *
     * @param graph      fragmentation graph from which the tree should be built
     * @param lowerbound minimal score of the tree. Higher lowerbounds may result in better runtime performance
     * @return an optimal fragmentation tree with at least lowerbound score or null, if no such tree exist
     */
    public FTree computeTree(FGraph graph, double lowerbound) {
        return computeTree(graph, lowerbound, recalibrationMethod != null);
    }

    /**
     * Compute a single fragmentation tree
     *
     * @param graph         fragmentation graph from which the tree should be built
     * @param lowerbound    minimal score of the tree. Higher lowerbounds may result in better runtime performance
     * @param recalibration if true, the tree will be recalibrated
     * @return an optimal fragmentation tree with at least lowerbound score or null, if no such tree exist
     */
    public FTree computeTree(FGraph graph, double lowerbound, boolean recalibration) {
        FTree tree = treeBuilder.buildTree(graph.getAnnotationOrThrow(ProcessedInput.class), graph, lowerbound);
        if (tree == null) return null;
        addTreeAnnotations(graph, tree);
        if (recalibration) tree = recalibrate(tree);
        return tree;
    }

    protected static class Stackitem {
        private final Fragment treeNode;
        private final Fragment graphNode;

        protected Stackitem(Fragment treeNode, Fragment graphNode) {
            this.treeNode = treeNode;
            this.graphNode = graphNode;
        }
    }

    /**
     * add annotations to the tree class
     * basically, the annotation system allows you to put arbitrary meta information into the tree
     * However, many of these information might be required by other algorithms (like the Peak annotation).
     * Therefore, this method tries to add as much metainformation as possible into the tree.
     * Only annotations that are registered in DescriptorRegistry (package BabelMs) will be serialized together
     * with the tree. Feel free to add "temporary non-persistent" annotations. Annotations should be
     * final immutable pojos.
     * @param originalGraph
     * @param tree
     */
    protected void addTreeAnnotations(FGraph originalGraph, FTree tree) {
        tree.addAnnotation(ProcessedInput.class, originalGraph.getAnnotationOrNull(ProcessedInput.class));

        // tree annotations
        tree.addAnnotation(PrecursorIonType.class, originalGraph.getAnnotationOrNull(PrecursorIonType.class));
        final TreeScoring treeScoring = new TreeScoring();
        treeScoring.setRootScore(originalGraph.getLoss(originalGraph.getRoot(), tree.getRoot().getFormula()).getWeight());

        // calculate overall score
        double overallScore = 0d;
        for (Loss l : tree.losses()) {
            overallScore += l.getWeight();
        }
        treeScoring.setOverallScore(treeScoring.getRootScore() + overallScore);

        tree.addAnnotation(TreeScoring.class, treeScoring);

        final FragmentAnnotation<Ms2IsotopePattern> msIsoAno;

        // fragment annotations
        final FragmentAnnotation<AnnotatedPeak> peakAnnotation = tree.getOrCreateFragmentAnnotation(AnnotatedPeak.class);
        final FragmentAnnotation<Peak> simplePeakAnnotation = tree.getOrCreateFragmentAnnotation(Peak.class);
        // non-persistent fragment annotation
        final FragmentAnnotation<ProcessedPeak> peakAno = tree.getOrCreateFragmentAnnotation(ProcessedPeak.class);

        final HashMap<MolecularFormula, Fragment> formula2graphFragment = new HashMap<MolecularFormula, Fragment>();
        for (Fragment f : tree) {
            formula2graphFragment.put(f.getFormula(), f);
        }
        for (Fragment f : originalGraph) {
            final MolecularFormula form = f.getFormula();
            if (form != null && formula2graphFragment.containsKey(form))
                formula2graphFragment.put(form, f);
        }

        // remove pseudo nodes
        if (originalGraph.getFragmentAnnotationOrNull(IsotopicMarker.class)!=null) {
            final FragmentAnnotation<IsotopicMarker> marker = tree.getOrCreateFragmentAnnotation(IsotopicMarker.class);
            msIsoAno = tree.getOrCreateFragmentAnnotation(Ms2IsotopePattern.class);
            final FragmentAnnotation<Ms2IsotopePattern> msIsoAnoG = originalGraph.getOrCreateFragmentAnnotation(Ms2IsotopePattern.class);
            final ArrayList<Fragment> subtreesToDelete = new ArrayList<Fragment>();
            for (Fragment f : tree) {
                if (msIsoAnoG.get(formula2graphFragment.get(f.getFormula()))!=null) {
                    // find isotope chain
                    double score = 0d;
                    int count = 1;
                    for (Fragment fchild : f.getChildren()) {
                        if (fchild.getFormula().isEmpty()) {
                            Fragment child = fchild;
                            while (true) {
                                ++count;
                                score += child.getIncomingEdge().getWeight();
                                marker.set(child, new IsotopicMarker());
                                if (child.isLeaf()) break;
                                else child = child.getChildren(0);
                            }
                            subtreesToDelete.add(fchild);
                            break;
                        }
                    }
                    if (count > 1) {
                        final Ms2IsotopePattern origPattern = msIsoAnoG.get(formula2graphFragment.get(f.getFormula()));
                        final Peak[] shortened = Arrays.copyOf(origPattern.getPeaks(), count);
                        // TODO: what happens if second peak is below threshold but third peak not?
                        msIsoAno.set(f, new Ms2IsotopePattern(shortened, score));
                    }
                }
            }
            for (Fragment f : subtreesToDelete) tree.deleteSubtree(f);
        } else msIsoAno = null;

        final FragmentAnnotation<ProcessedPeak> graphPeakAno = originalGraph.getFragmentAnnotationOrThrow(ProcessedPeak.class);
        for (Fragment treeFragment : tree) {
            final Fragment graphFragment = formula2graphFragment.get(treeFragment.getFormula());
            final ProcessedPeak graphPeak = graphPeakAno.get(graphFragment);
            peakAno.set(treeFragment, graphPeak);
            simplePeakAnnotation.set(treeFragment, graphPeak);
            peakAnnotation.set(treeFragment, graphPeak.toAnnotatedPeak(treeFragment.getFormula()));
        }

        // add statistics
        treeScoring.setExplainedIntensity(getIntensityRatioOfExplainedPeaks(tree));
        treeScoring.setExplainedIntensityOfExplainablePeaks(getIntensityRatioOfExplainablePeaks(tree));
        treeScoring.setRatioOfExplainedPeaks((double)tree.numberOfVertices() / (double)originalGraph.getAnnotationOrThrow(ProcessedInput.class).getMergedPeaks().size());
    }

    /**
     * Recalibrates the tree
     *
     * @return Recalibration object containing score bonus and new tree
     */
    public RecalibrationMethod.Recalibration getRecalibrationFromTree(final FTree tree, boolean force) {
        if (recalibrationMethod == null || tree == null) return null;
        else return recalibrationMethod.recalibrate(tree, new MassDeviationVertexScorer(), force);
    }

    public RecalibrationMethod.Recalibration getRecalibrationFromTree(final FTree tree) {
        return getRecalibrationFromTree(tree, false);
    }

    /**
     * Recalibrates the tree. Returns either a new recalibrated tree or the old tree with recalibrated deviations and
     * (maybe) higher scores. The FragmentationTree#getRecalibrationBonus returns the improvement of the score after
     * recalibration
     *
     * @param tree
     * @return
     */
    public FTree recalibrate(FTree tree, boolean force) {
        if (tree == null) return null;
        final TreeScoring treeScoring = tree.getAnnotationOrThrow(TreeScoring.class);
        RecalibrationMethod.Recalibration rec = getRecalibrationFromTree(tree, force);
        final UnivariateFunction func = (rec == null) ? null : rec.recalibrationFunction();
        assert (func == null || (!(func instanceof PolynomialFunction) || ((PolynomialFunction) func).degree() >= 1));
        if (rec == null || (!force && rec.getScoreBonus() <= 0)) return tree;
        double oldScore = treeScoring.getOverallScore();
        if (force || rec.shouldRecomputeTree()) {
            final FTree newTree = rec.getCorrectedTree(this, tree);
            final TreeScoring newTreeScoring = newTree.getAnnotationOrThrow(TreeScoring.class);
            setRecalibrationBonusFromTree(tree, newTree);
            if (force || newTreeScoring.getRecalibrationBonus()>0) {
                tree = newTree;
            }
        } else {
            treeScoring.setOverallScore(treeScoring.getOverallScore() + rec.getScoreBonus());
            treeScoring.setRecalibrationBonus(rec.getScoreBonus());
        }
        if (func != null) {
            final RecalibrationFunction f = toPolynomial(func);
            if (f!=null)
                tree.addAnnotation(RecalibrationFunction.class, f);
        }
        return tree;
    }

    private static void setRecalibrationBonusFromTree(FTree old, FTree newTree) {
        final TreeScoring a = old.getAnnotationOrThrow(TreeScoring.class);
        final TreeScoring b = newTree.getAnnotationOrThrow(TreeScoring.class);
        double aS = a.getOverallScore();
        double bS = b.getOverallScore();
        for (Map.Entry<String, Double> e : a.getAdditionalScores().entrySet()) {
            if (b.getAdditionalScore(e.getKey())==0) aS -= e.getValue();
        }
        b.setRecalibrationBonus(bS-aS);
    }

    static RecalibrationFunction toPolynomial(UnivariateFunction func) {
        if (func instanceof PolynomialFunction) {
            return new RecalibrationFunction(((PolynomialFunction) func).getCoefficients());
        }
        if (func instanceof Identity) return RecalibrationFunction.identity();
        return null;
    }

    public FTree recalibrate(FTree tree) {
        return recalibrate(tree, false);
    }

    public RecalibrationMethod getRecalibrationMethod() {
        return recalibrationMethod;
    }

    public void setRecalibrationMethod(RecalibrationMethod recalibrationMethod) {
        this.recalibrationMethod = recalibrationMethod;
    }

    /**
     * Computes a fragmentation tree
     *
     * @param graph fragmentation graph from which the tree should be built
     * @return an optimal fragmentation tree
     */
    public FTree computeTree(FGraph graph) {
        return computeTree(graph, Double.NEGATIVE_INFINITY);
    }

    public MultipleTreeComputation computeTrees(ProcessedInput input) {
        return new MultipleTreeComputation(this, input, input.getPeakAnnotationOrThrow(DecompositionList.class).get(input.getParentPeak()).getDecompositions(),
                0, Integer.MAX_VALUE, 1, recalibrationMethod != null, null);
    }

    public GraphReduction getReduction() {
        return reduction;
    }

    public void setReduction(GraphReduction reduction) {
        this.reduction = reduction;
    }

    public FGraph buildGraph(ProcessedInput input, Scored<MolecularFormula> candidate) {
        // build Graph
        final FGraph graph = graphBuilder.fillGraph(
                graphBuilder.addRoot(graphBuilder.initializeEmptyGraph(input),
                        input.getParentPeak(), Collections.singletonList(candidate)));
        graph.addAliasForFragmentAnnotation(ProcessedPeak.class, Peak.class);
        return performGraphReduction(performGraphScoring(graph));
    }

    public FGraph performGraphReduction(FGraph fragments, double lowerbound) {
        if (reduction == null) return fragments;
        return reduction.reduce(fragments, lowerbound);
    }

    public FGraph performGraphReduction(FGraph fragments) {
        if (reduction == null) return fragments;
        return reduction.reduce(fragments, 0d);
    }

    public FGraph buildGraph(ProcessedInput input, List<ProcessedPeak> parentPeaks, List<List<Scored<MolecularFormula>>> candidatesPerParentPeak) {
        // build Graph
        FGraph graph = graphBuilder.initializeEmptyGraph(input);
        for (int i = 0; i < parentPeaks.size(); ++i) {
            graph = graphBuilder.addRoot(graph, parentPeaks.get(i), candidatesPerParentPeak.get(i));
        }
        return performGraphReduction(performGraphScoring(graphBuilder.fillGraph(graph)));
    }

    /**
     * @return the relative amount of intensity that is explained by this tree, considering only
     * peaks that have an explanation for the hypothetical precursor ion of the tree
     */
    public double getIntensityRatioOfExplainablePeaks(FTree tree) {
        double treeIntensity = 0d, maxIntensity = 0d;
        final FragmentAnnotation<ProcessedPeak> pp = tree.getFragmentAnnotationOrThrow(ProcessedPeak.class);
        for (Fragment f : tree.getFragmentsWithoutRoot()) treeIntensity += pp.get(f).getRelativeIntensity();
        final ProcessedInput input = tree.getAnnotationOrThrow(ProcessedInput.class);
        final PeakAnnotation<DecompositionList> decomp = input.getPeakAnnotationOrThrow(DecompositionList.class);
        final MolecularFormula parent = tree.getRoot().getFormula();
        eachPeak:
        for (ProcessedPeak p : input.getMergedPeaks())
            if (p != input.getParentPeak()) {
                for (Scored<MolecularFormula> f : decomp.get(p).getDecompositions()) {
                    if (parent.isSubtractable(f.getCandidate())) {
                        maxIntensity += p.getRelativeIntensity();
                        continue eachPeak;
                    }
                }
            }
        if (maxIntensity==0) return 0;
        return treeIntensity / maxIntensity;
    }

    /**
     * @return the relative amount of intensity that is explained by this tree
     */
    public double getIntensityRatioOfExplainedPeaks(FTree tree) {
        double treeIntensity = 0d, maxIntensity = 0d;
        final FragmentAnnotation<ProcessedPeak> pp = tree.getFragmentAnnotationOrThrow(ProcessedPeak.class);
        for (Fragment f : tree.getFragmentsWithoutRoot()) treeIntensity += pp.get(f).getRelativeIntensity();
        final ProcessedInput input = tree.getAnnotationOrThrow(ProcessedInput.class);
        final PeakAnnotation<DecompositionList> decomp = input.getPeakAnnotationOrThrow(DecompositionList.class);
        final MolecularFormula parent = tree.getRoot().getFormula();
        eachPeak:
        for (ProcessedPeak p : input.getMergedPeaks())
            if (p != input.getParentPeak()) {
                maxIntensity += p.getRelativeIntensity();
            }
        if (maxIntensity==0) return 0;
        return treeIntensity / maxIntensity;
    }

    /**
     * Adds Score annotations to vertices and losses of the tree for every scoring method.
     * As the single scores are forgotten during tree computation, they have to be computed again.
     * @param tree
     */
    public boolean recalculateScores(FTree tree) {
        final Iterator<Loss> edges = tree.lossIterator();
        final ProcessedInput input = tree.getAnnotationOrThrow(ProcessedInput.class);
        final FragmentAnnotation<ProcessedPeak> peakAno = tree.getFragmentAnnotationOrThrow(ProcessedPeak.class);

        final Object[] preparedLoss = new Object[lossScorers.size()];
        final Object[] preparedFrag = new Object[decompositionScorers.size()];
        final FragmentAnnotation<Ms2IsotopePattern> msIso = tree.getFragmentAnnotationOrNull(Ms2IsotopePattern.class);
        final String[] fragmentScores;
        final String[] lossScores;
        final String[] rootScores;
        {
            final ArrayList<String> fragScores = new ArrayList<String>();
            for (PeakScorer peakScorer : this.fragmentPeakScorers) {
                fragScores.add(getScoringMethodName(peakScorer));
            }
            int i=0;
            for (DecompositionScorer peakScorer : this.decompositionScorers) {
                fragScores.add(getScoringMethodName(peakScorer));
                preparedFrag[i++] = peakScorer.prepare(input);
            }
            if (msIso!=null) fragScores.add("isotopes");
            fragmentScores = fragScores.toArray(new String[fragScores.size()]);
        }
        {
            final ArrayList<String> lScores = new ArrayList<String>();
            for (PeakPairScorer lossScorer : this.peakPairScorers) {
                lScores.add(getScoringMethodName(lossScorer));
            }
            int i=0;
            for (LossScorer lossScorer : this.lossScorers) {
                lScores.add(getScoringMethodName(lossScorer));
                preparedLoss[i++] = lossScorer.prepare(input);
            }
            lossScores = lScores.toArray(new String[lScores.size()]);
        }
        {
            final ArrayList<String> fragScores = new ArrayList<String>();
            for (DecompositionScorer peakScorer : this.rootScorers) {
                fragScores.add(getScoringMethodName(peakScorer));
            }
            rootScores = fragScores.toArray(new String[fragScores.size()]);
        }

        final FragmentAnnotation<Score> fAno = tree.getOrCreateFragmentAnnotation(Score.class);
        final LossAnnotation<Score> lAno = tree.getOrCreateLossAnnotation(Score.class);
        final LossAnnotation<InsourceFragmentation> isInsource = tree.getOrCreateLossAnnotation(InsourceFragmentation.class);
        final double[][] pseudoMatrix = new double[2][2];
        while (edges.hasNext()) {
            final Loss loss = edges.next();
            if (isInsource.get(loss)!= null && isInsource.get(loss).isInsource()) continue;
            final Fragment u = loss.getSource();
            final Fragment v = loss.getTarget();

            // add loss scores
            final Score lscore = new Score(lossScores);
            int k=0;
            for (int i=0; i < peakPairScorers.size(); ++i) {
                pseudoMatrix[0][0]=pseudoMatrix[0][1]=pseudoMatrix[1][0]=pseudoMatrix[1][1]=0.0d;
                peakPairScorers.get(i).score(Arrays.asList(peakAno.get(v), peakAno.get(u)), input,pseudoMatrix);
                lscore.set(k++, pseudoMatrix[1][0]);
            }
            for (int i=0; i < lossScorers.size(); ++i) {
                lscore.set(k++, lossScorers.get(i).score(loss, input, preparedLoss[i]));
            }
            lAno.set(loss, lscore);

            // add fragment scores
            final Score fscore = new Score(fragmentScores);
            k=0;
            for (int i=0; i < fragmentPeakScorers.size(); ++i) {
                pseudoMatrix[0][0]=pseudoMatrix[0][1]=pseudoMatrix[1][0]=pseudoMatrix[1][1]=0.0d;
                fragmentPeakScorers.get(i).score(Arrays.asList(peakAno.get(v)), input,pseudoMatrix[0]);
                fscore.set(k++, pseudoMatrix[0][0]);
            }
            for (int i=0; i < decompositionScorers.size(); ++i) {
                fscore.set(k++, ((DecompositionScorer<Object>) decompositionScorers.get(i)).score(v.getFormula(), peakAno.get(v), input, preparedFrag[i]));
            }

            if (msIso!=null && msIso.get(v)!=null) {
                final double score = msIso.get(v).getScore();
                fscore.set("isotopes", score);
            }

            fAno.set(v, fscore);
        }
        // set root
        Fragment root = tree.getRoot();
        if (root.getOutDegree()==1 && isInsource.get(root.getOutgoingEdge(0))!=null && isInsource.get(root.getOutgoingEdge(0)).isInsource()) {
            root = root.getChildren(0);
        }
        final Score rootScore = new Score(rootScores);
        for (int k=0; k < rootScorers.size(); ++k) {
            final Object prepared = rootScorers.get(k).prepare(input);
            final double score = ((DecompositionScorer<Object>)rootScorers.get(k)).score(root.getFormula(), peakAno.get(root), input, prepared);
            rootScore.set(k, score);
        }
        fAno.set(root, rootScore);

        // check scoreSum
        double scoreSum = 0d;
        for (Loss l : tree.losses()) {
            Score s = lAno.get(l);
            if (s==null) continue;
            scoreSum += s.sum();
            s = fAno.get(l.getTarget());
            scoreSum += s.sum();
        }
        scoreSum += fAno.get(root).sum();
        return Math.abs(scoreSum-tree.getAnnotationOrThrow(TreeScoring.class).getOverallScore()) < 1e-8;

    }

    private static String getScoringMethodName(Object instance) {
        Class<? extends Object> someClass = instance.getClass();
        if (someClass.isAnnotationPresent(Called.class)) {
            return someClass.getAnnotation(Called.class).value();
        } else return parameterHelper.toClassName(someClass);
    }

    public FGraph performGraphScoring(FGraph graph) {
        // score graph
        final Iterator<Loss> edges = graph.lossIterator();
        final ProcessedInput input = graph.getAnnotationOrThrow(ProcessedInput.class);
        final Scoring scoring = input.getAnnotationOrThrow(Scoring.class);
        final double[] peakScores = scoring.getPeakScores();
        final double[][] peakPairScores = scoring.getPeakPairScores();
        final LossScorer[] lossScorers = this.lossScorers.toArray(new LossScorer[this.lossScorers.size()]);
        final Object[] precomputeds = new Object[lossScorers.length];
        final ScoredFormulaMap map = graph.getAnnotationOrThrow(ScoredFormulaMap.class);
        final FragmentAnnotation<ProcessedPeak> peakAno = graph.getFragmentAnnotationOrThrow(ProcessedPeak.class);
        for (int i = 0; i < precomputeds.length; ++i) precomputeds[i] = lossScorers[i].prepare(input);
        while (edges.hasNext()) {
            final Loss loss = edges.next();
            final Fragment u = loss.getSource();
            final Fragment v = loss.getTarget();
            // take score of molecular formula
            double score = map.get(v.getFormula());
            assert !Double.isInfinite(score);
            // add it to score of the peak
            score += peakScores[peakAno.get(v).getIndex()];
            assert !Double.isInfinite(score);
            // add it to the score of the peak pairs
            if (!u.isRoot())
                score += peakPairScores[peakAno.get(u).getIndex()][peakAno.get(v).getIndex()]; // TODO: Umdrehen!
            assert !Double.isInfinite(score);
            // add the score of the loss
            if (!u.isRoot())
                for (int i = 0; i < lossScorers.length; ++i)
                    score += lossScorers[i].score(loss, input, precomputeds[i]);
            assert !Double.isInfinite(score);
            loss.setWeight(score);
        }

        ///////////////////
        if (isoInMs2Scorer!=null) isoInMs2Scorer.score(input, graph);
        ///////////////////

        return graph;
    }

    private void addSyntheticParent(Ms2Experiment experiment, List<ProcessedPeak> processedPeaks, double parentmass) {
        final ProcessedPeak syntheticParent = new ProcessedPeak();
        syntheticParent.setIon(experiment.getPrecursorIonType().getIonization());
        syntheticParent.setMz(parentmass);
        syntheticParent.setOriginalMz(parentmass);
        processedPeaks.add(syntheticParent);
    }

    public void enableIsotopesInMs2(boolean value) {
        if (value) isoInMs2Scorer = new IsotopePatternInMs2Scorer();
        else isoInMs2Scorer=null;
    }

    /*

    Merging:
        - 1. lösche alle Peaks die zu nahe an einem anderen Peak im selben Spektrum sind un geringe Intensität
        - 2. der Peakmerger bekommt nur Peak aus unterschiedlichen Spektren und mergt diese
        - 3. Nach der Decomposition läuft man alle peaks in der Liste durch. Wenn zwischen zwei
             Peaks der Abstand zu klein wird, werden diese Peaks disjunkt, in dem die doppelt vorkommenden
             Decompositions auf einen peak (den mit der geringeren asseabweichung) eindeutig verteilt werden.

     */


    ProcessedInput postProcess(PostProcessor.Stage stage, ProcessedInput input) {
        for (PostProcessor proc : postProcessors) {
            if (proc.getStage() == stage) {
                input = proc.process(input);
            }
        }
        return input;
    }

    //////////////////////////////////////////
    //        GETTER/SETTER
    //////////////////////////////////////////


    public List<InputValidator> getInputValidators() {
        return inputValidators;
    }

    public void setInputValidators(List<InputValidator> inputValidators) {
        this.inputValidators = inputValidators;
    }

    public Warning getValidatorWarning() {
        return validatorWarning;
    }

    public void setValidatorWarning(Warning validatorWarning) {
        this.validatorWarning = validatorWarning;
    }

    public boolean isRepairInput() {
        return repairInput;
    }

    public void setRepairInput(boolean repairInput) {
        this.repairInput = repairInput;
    }

    public NormalizationType getNormalizationType() {
        return normalizationType;
    }

    public void setNormalizationType(NormalizationType normalizationType) {
        this.normalizationType = normalizationType;
    }

    public PeakMerger getPeakMerger() {
        return peakMerger;
    }

    public void setPeakMerger(PeakMerger peakMerger) {
        this.peakMerger = peakMerger;
    }

    public List<DecompositionScorer<?>> getDecompositionScorers() {
        return decompositionScorers;
    }

    public void setDecompositionScorers(List<DecompositionScorer<?>> decompositionScorers) {
        this.decompositionScorers = decompositionScorers;
    }

    public List<DecompositionScorer<?>> getRootScorers() {
        return rootScorers;
    }

    public void setRootScorers(List<DecompositionScorer<?>> rootScorers) {
        this.rootScorers = rootScorers;
    }

    public List<LossScorer> getLossScorers() {
        return lossScorers;
    }

    public void setLossScorers(List<LossScorer> lossScorers) {
        this.lossScorers = lossScorers;
    }

    public List<PeakPairScorer> getPeakPairScorers() {
        return peakPairScorers;
    }

    public void setPeakPairScorers(List<PeakPairScorer> peakPairScorers) {
        this.peakPairScorers = peakPairScorers;
    }

    public List<PeakScorer> getFragmentPeakScorers() {
        return fragmentPeakScorers;
    }

    public void setFragmentPeakScorers(List<PeakScorer> fragmentPeakScorers) {
        this.fragmentPeakScorers = fragmentPeakScorers;
    }

    public List<Preprocessor> getPreprocessors() {
        return preprocessors;
    }

    public void setPreprocessors(List<Preprocessor> preprocessors) {
        this.preprocessors = preprocessors;
    }

    public List<PostProcessor> getPostProcessors() {
        return postProcessors;
    }

    public void setPostProcessors(List<PostProcessor> postProcessors) {
        this.postProcessors = postProcessors;
    }

    public TreeBuilder getTreeBuilder() {
        return treeBuilder;
    }

    public void setTreeBuilder(TreeBuilder treeBuilder) {
        this.treeBuilder = treeBuilder;
    }

    public MutableMeasurementProfile getDefaultProfile() {
        return defaultProfile;
    }

    public void setDefaultProfile(MeasurementProfile defaultProfile) {
        this.defaultProfile = new MutableMeasurementProfile(defaultProfile);
    }

    public MassToFormulaDecomposer getDecomposerFor(ChemicalAlphabet alphabet) {
        return decomposers.getDecomposer(alphabet);
    }

    public DecomposerCache getDecomposerCache() {
        return decomposers;
    }

    @Override
    public <G, D, L> void importParameters(ParameterHelper helper, DataDocument<G, D, L> document, D dictionary) {
        setInitial();
        fillList(preprocessors, helper, document, dictionary, "preProcessing");
        fillList(postProcessors, helper, document, dictionary, "postProcessing");
        fillList(rootScorers, helper, document, dictionary, "rootScorers");
        fillList(decompositionScorers, helper, document, dictionary, "fragmentScorers");
        fillList(fragmentPeakScorers, helper, document, dictionary, "peakScorers");
        fillList(peakPairScorers, helper, document, dictionary, "peakPairScorers");
        fillList(lossScorers, helper, document, dictionary, "lossScorers");
        peakMerger = (PeakMerger) helper.unwrap(document, document.getFromDictionary(dictionary, "merge"));
        if (document.hasKeyInDictionary(dictionary, "recalibrationMethod")) {
            recalibrationMethod = (RecalibrationMethod) helper.unwrap(document, document.getFromDictionary(dictionary, "recalibrationMethod"));
        } else recalibrationMethod = null;
        if (document.hasKeyInDictionary(dictionary, "default"))
            defaultProfile = new MutableMeasurementProfile((MeasurementProfile) helper.unwrap(document, document.getFromDictionary(dictionary, "default")));
        else
            defaultProfile = null;

    }

    private <T, G, D, L> void fillList(List<T> list, ParameterHelper helper, DataDocument<G, D, L> document, D dictionary, String keyName) {
        if (!document.hasKeyInDictionary(dictionary, keyName)) return;
        Iterator<G> ls = document.iteratorOfList(document.getListFromDictionary(dictionary, keyName));
        while (ls.hasNext()) {
            final G l = ls.next();
            list.add((T) helper.unwrap(document, l));
        }
    }

    @Override
    public <G, D, L> void exportParameters(ParameterHelper helper, DataDocument<G, D, L> document, D dictionary) {
        exportParameters(helper, document, dictionary, true);
    }

    protected <G, D, L> void exportParameters(ParameterHelper helper, DataDocument<G, D, L> document, D dictionary, boolean withProfile) {
        L list = document.newList();
        for (Preprocessor p : preprocessors) document.addToList(list, helper.wrap(document, p));
        document.addListToDictionary(dictionary, "preProcessing", list);
        list = document.newList();
        for (PostProcessor p : postProcessors) document.addToList(list, helper.wrap(document, p));
        document.addListToDictionary(dictionary, "postProcessing", list);
        list = document.newList();
        for (DecompositionScorer s : rootScorers) document.addToList(list, helper.wrap(document, s));
        document.addListToDictionary(dictionary, "rootScorers", list);
        list = document.newList();
        for (DecompositionScorer s : decompositionScorers) document.addToList(list, helper.wrap(document, s));
        document.addListToDictionary(dictionary, "fragmentScorers", list);
        list = document.newList();
        for (PeakScorer s : fragmentPeakScorers) document.addToList(list, helper.wrap(document, s));
        document.addListToDictionary(dictionary, "peakScorers", list);
        list = document.newList();
        for (PeakPairScorer s : peakPairScorers) document.addToList(list, helper.wrap(document, s));
        document.addListToDictionary(dictionary, "peakPairScorers", list);
        list = document.newList();
        for (LossScorer s : lossScorers) document.addToList(list, helper.wrap(document, s));
        document.addListToDictionary(dictionary, "lossScorers", list);
        if (recalibrationMethod != null)
            document.addToDictionary(dictionary, "recalibrationMethod", helper.wrap(document, recalibrationMethod));
        document.addToDictionary(dictionary, "merge", helper.wrap(document, peakMerger));
        if (withProfile)
            document.addToDictionary(dictionary, "default", helper.wrap(document, new MutableMeasurementProfile(defaultProfile)));

    }
}
