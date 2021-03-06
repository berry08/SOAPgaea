package org.bgi.flexlab.gaea.tools.haplotypecaller.pileup;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;

import org.bgi.flexlab.gaea.data.structure.bam.GaeaSamRecord;
import org.bgi.flexlab.gaea.data.structure.location.GenomeLocation;
import org.bgi.flexlab.gaea.data.structure.reference.ChromosomeInformationShare;
import org.bgi.flexlab.gaea.tools.haplotypecaller.DownsamplingMethod;
import org.bgi.flexlab.gaea.tools.haplotypecaller.Shard;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.ActivityProfile;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.BandPassActivityProfile;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.ActivityProfileState;
import org.bgi.flexlab.gaea.tools.haplotypecaller.assembly.AssemblyRegion;
import org.bgi.flexlab.gaea.tools.haplotypecaller.engine.HaplotypeCallerEngine;
import org.bgi.flexlab.gaea.tools.haplotypecaller.utils.RefMetaDataTracker;
import org.bgi.flexlab.gaea.util.ReadUtils;
import org.bgi.flexlab.gaea.util.Utils;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.util.Locatable;

public class AssemblyRegionIterator implements Iterator<AssemblyRegion> {

    private final Shard<GaeaSamRecord> readShard;
    private final SAMFileHeader readHeader;
    private final ChromosomeInformationShare reference;
    private final RefMetaDataTracker features;
    private final HaplotypeCallerEngine evaluator;
    private final int minRegionSize;
    private final int maxRegionSize;
    private final int assemblyRegionPadding;
    private final double activeProbThreshold;
    private final int maxProbPropagationDistance;
    
    private AssemblyRegion readyRegion;
    private Queue<AssemblyRegion> pendingRegions;
    private List<GaeaSamRecord> previousRegionReads;
    private final ReadCachingIterator readCachingIterator;
    private Queue<GaeaSamRecord> readCache;
    private final Iterator<AlignmentContext> locusIterator;
    private final ActivityProfile activityProfile;

    /**
     * Constructs an AssemblyRegionIterator over a provided read shard
     *
     * @param readShard Shard containing the reads that will go into the assembly regions. Must have a MAPPED filter set on it.
     * @param readHeader header for the reads
     * @param reference source of reference bases (may be null)
     * @param features source of arbitrary features (may be null)
     * @param evaluator evaluator used to determine whether a locus is active
     * @param minRegionSize minimum size of an assembly region
     * @param maxRegionSize maximum size of an assembly region
     * @param assemblyRegionPadding number of bases of padding on either side of an assembly region
     * @param activeProbThreshold minimum probability for a locus to be considered active
     * @param maxProbPropagationDistance upper limit on how many bases away probability mass can be moved around
     *                                   when calculating the boundaries between active and inactive assembly regions
     */
    public AssemblyRegionIterator(final Shard<GaeaSamRecord> readShard,
                                  final SAMFileHeader readHeader,
                                  final ChromosomeInformationShare reference,
                                  final RefMetaDataTracker features,
                                  final HaplotypeCallerEngine evaluator,
                                  final int minRegionSize,
                                  final int maxRegionSize,
                                  final int assemblyRegionPadding,
                                  final double activeProbThreshold,
                                  final int maxProbPropagationDistance,
                                  final int maxReadsPerPosition,
                                  final boolean includeReadsWithDeletionsInIsActivePileups) {

        Utils.nonNull(readShard);
        Utils.nonNull(readHeader);
        Utils.nonNull(evaluator);
        Utils.validateArg(minRegionSize >= 1, "minRegionSize must be >= 1");
        Utils.validateArg(maxRegionSize >= 1, "maxRegionSize must be >= 1");
        Utils.validateArg(minRegionSize <= maxRegionSize, "minRegionSize must be <= maxRegionSize");
        Utils.validateArg(assemblyRegionPadding >= 0, "assemblyRegionPadding must be >= 0");
        Utils.validateArg(activeProbThreshold >= 0.0, "activeProbThreshold must be >= 0.0");
        Utils.validateArg(maxProbPropagationDistance >= 0, "maxProbPropagationDistance must be >= 0");

        this.readShard = readShard;
        this.readHeader = readHeader;
        this.reference = reference;
        this.features = features;
        this.evaluator = evaluator;
        this.minRegionSize = minRegionSize;
        this.maxRegionSize = maxRegionSize;
        this.assemblyRegionPadding = assemblyRegionPadding;
        this.activeProbThreshold = activeProbThreshold;
        this.maxProbPropagationDistance = maxProbPropagationDistance;

        this.readyRegion = null;
        this.previousRegionReads = null;
        this.pendingRegions = new ArrayDeque<>();
        this.readCachingIterator = new ReadCachingIterator(readShard.iterator());
        this.readCache = new ArrayDeque<>();
        this.activityProfile = new BandPassActivityProfile(null, maxProbPropagationDistance, activeProbThreshold, BandPassActivityProfile.MAX_FILTER_SIZE, BandPassActivityProfile.DEFAULT_SIGMA, readHeader);

        // We wrap our LocusIteratorByState inside an AllLocusIterator so that we get empty loci
        // for uncovered locations. This is critical for reproducing GATK 3.x behavior!
        DownsamplingMethod samplingMethod = DownsamplingMethod.NONE;
        if(maxReadsPerPosition > 0)
        	samplingMethod = new DownsamplingMethod(DownsamplingMethod.DEFAULT_DOWNSAMPLING_TYPE,maxReadsPerPosition,null);
        
        //final LocusIteratorByState libs = new LocusIteratorByState(readCachingIterator, DownsamplingMethod.NONE, false, ReadUtils.getSamplesFromHeader(readHeader), readHeader, includeReadsWithDeletionsInIsActivePileups);
        final LocusIteratorByState libs = new LocusIteratorByState(readCachingIterator,samplingMethod, false, ReadUtils.getSamplesFromHeader(readHeader), readHeader, includeReadsWithDeletionsInIsActivePileups);
        this.locusIterator = new AllLocusIterator(readShard.getPaddedInterval(), libs);

        readyRegion = loadNextAssemblyRegion();
    }

    @Override
    public boolean hasNext() {
        return readyRegion != null;
    }

    @Override
    public AssemblyRegion next() {
        if ( ! hasNext() ) {
            throw new NoSuchElementException("next() called when there were no more elements");
        }

        final AssemblyRegion toReturn = readyRegion;
        previousRegionReads = toReturn.getReads();
        readyRegion = loadNextAssemblyRegion();
        return toReturn;
    }

    private AssemblyRegion loadNextAssemblyRegion() {
        AssemblyRegion nextRegion = null;

        while ( locusIterator.hasNext() && nextRegion == null ) {
            final AlignmentContext pileup = locusIterator.next();

           // Ignore sites that are not contained within this shard's interval
//            if ( ! readShard.getInterval().contains(pileup) ) {
//                continue;
//            }

//            if( ! readShard.getInterval().overlaps(pileup))
//                continue;

            // Pop any new pending regions off of the activity profile. These pending regions will not become ready
            // until we've traversed all the reads that belong in them.
            //
            // Ordering matters here: need to check for forceConversion before adding current pileup to the activity profile
            if ( ! activityProfile.isEmpty() ) {
                final boolean forceConversion = pileup.getLocation().getStart() != activityProfile.getEnd() + 1;
                pendingRegions.addAll(activityProfile.popReadyAssemblyRegions(assemblyRegionPadding, minRegionSize, maxRegionSize, forceConversion));
            }

            // Add the current pileup to the activity profile
            final GenomeLocation pileupInterval = new GenomeLocation(pileup);

            final ActivityProfileState profile = evaluator.isActive(pileup, reference, features,pileupInterval);
            activityProfile.add(profile);

            // A pending region only becomes ready once our locus iterator has advanced beyond the end of its extended span
            // (this ensures that we've loaded all reads that belong in the new region)
            if ( ! pendingRegions.isEmpty() && isAfter(pileup.getLocation(), pendingRegions.peek().getExtendedSpan(), readHeader.getSequenceDictionary()) ) {
                nextRegion = pendingRegions.poll();
            }
            pileup.clear();
        }

        // When we run out of loci, close out the activity profile, and close out any remaining pending regions one at a time
        // It may require multiple invocations before the pendingRegions queue is cleared out.
        if ( ! locusIterator.hasNext() ) {
            if ( ! activityProfile.isEmpty() ) {
                // Pop the activity profile a final time with forceConversion == true
                pendingRegions.addAll(activityProfile.popReadyAssemblyRegions(assemblyRegionPadding, minRegionSize, maxRegionSize, true));
            }

            // Grab the next pending region if there is one, unless we already have a region ready to go
            // (could happen if we finalize a region on the same iteration that we run out of loci in the locus iterator)
            if ( ! pendingRegions.isEmpty() && nextRegion == null ) {
                nextRegion = pendingRegions.poll();
            }
        }

        // If there's a region ready, fill it with reads before returning
        if ( nextRegion != null ) {
            fillNextAssemblyRegionWithReads(nextRegion);
        }

        return nextRegion;
    }

    private void fillNextAssemblyRegionWithReads( final AssemblyRegion region ) {
        // First we need to check the previous region for reads that also belong in this region
        if ( previousRegionReads != null ) {
            for ( final GaeaSamRecord previousRegionRead : previousRegionReads ) {
                if ( region.getExtendedSpan().overlaps(previousRegionRead) ) {
                    region.add(previousRegionRead);
                }
            }
            previousRegionReads.clear();
        }

        // Update our read cache with the reads newly-read by our locus iterator.
        // Note that the cache is (crucially) maintained in coordinate order.
        readCache.addAll(readCachingIterator.consumeCachedReads());

        // Add all reads from the cache that belong in this region
        while ( ! readCache.isEmpty() ) {
            final GaeaSamRecord nextRead = readCache.peek();

            // Stop once we encounter a read that starts after the end of the last region's span
            // (and leave it in the readCache)
            if ( isAfter(nextRead, region.getExtendedSpan(), readHeader.getSequenceDictionary()) ) {
                break;
            }

            // Ok, we're going to consume this read
            readCache.poll();

            // Add the read if it overlaps the region's extended span. If it doesn't, it must end before the
            // start of the region's extended span, so we discard it.
            if ( region.getExtendedSpan().overlaps(nextRead) ) {
                region.add(nextRead);
            }
        }
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove() not supported by AssemblyRegionIterator");
    }
    
    public static boolean isAfter(final Locatable first, final Locatable second, final SAMSequenceDictionary dictionary) {
        Utils.nonNull(first);
        Utils.nonNull(second);
        Utils.nonNull(dictionary);

        final int contigComparison = compareContigs(first, second, dictionary);
        return contigComparison == 1 || (contigComparison == 0 && first.getStart() > second.getEnd());
    }
    
    public static int compareContigs(final Locatable first, final Locatable second, final SAMSequenceDictionary dictionary) {
        Utils.nonNull(first);
        Utils.nonNull(second);
        Utils.nonNull(dictionary);

        final int firstRefIndex = dictionary.getSequenceIndex(first.getContig());
        final int secondRefIndex = dictionary.getSequenceIndex(second.getContig());
        if (firstRefIndex == -1 || secondRefIndex == -1) {
            throw new IllegalArgumentException("Can't do comparison because Locatables' contigs not found in sequence dictionary");
        }
        // compare the contigs
        return Integer.compare(firstRefIndex, secondRefIndex);
    }
}

