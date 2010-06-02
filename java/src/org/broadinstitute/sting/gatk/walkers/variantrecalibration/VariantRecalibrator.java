/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.variantrecalibration;

import org.broad.tribble.dbsnp.DbSNPFeature;
import org.broad.tribble.vcf.*;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.contexts.variantcontext.VariantContext;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.refdata.VariantContextAdaptors;
import org.broadinstitute.sting.gatk.refdata.tracks.RMDTrack;
import org.broadinstitute.sting.gatk.refdata.utils.helpers.DbSNPHelper;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.collections.ExpandingArrayList;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.utils.genotype.vcf.VCFReader;
import org.broadinstitute.sting.utils.genotype.vcf.VCFUtils;
import org.broadinstitute.sting.utils.genotype.vcf.VCFWriter;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Applies calibrated variant cluster parameters to variant calls to produce an accurate and informative variant quality score
 *
 * @author rpoplin
 * @since Mar 17, 2010
 *
 * @help.summary Applies calibrated variant cluster parameters to variant calls to produce an accurate and informative variant quality score
 */

public class VariantRecalibrator extends RodWalker<ExpandingArrayList<VariantDatum>, ExpandingArrayList<VariantDatum>> {

    /////////////////////////////
    // Command Line Arguments
    /////////////////////////////
    @Argument(fullName="target_titv", shortName="titv", doc="The expected Ti/Tv ratio to display on optimization curve output figures. (~~2.1 for whole genome experiments)", required=false)
    private double TARGET_TITV = 2.1;
    @Argument(fullName="backOff", shortName="backOff", doc="The Gaussian back off factor, used to prevent overfitting by spreading out the Gaussians.", required=false)
    private double BACKOFF_FACTOR = 1.0;
    @Argument(fullName="desired_num_variants", shortName="dV", doc="The desired number of variants to keep in a theoretically filtered set", required=false)
    private int DESIRED_NUM_VARIANTS = 0;
    @Argument(fullName="ignore_all_input_filters", shortName="ignoreAllFilters", doc="If specified the optimizer will use variants even if the FILTER column is marked in the VCF file", required=false)
    private boolean IGNORE_ALL_INPUT_FILTERS = false;
    @Argument(fullName="ignore_filter", shortName="ignoreFilter", doc="If specified the optimizer will use variants even if the specified filter name is marked in the input VCF file", required=false)
    private String[] IGNORE_INPUT_FILTERS = null;
    @Argument(fullName="known_prior", shortName="knownPrior", doc="A prior on the quality of known variants, a phred scaled probability of being true.", required=false)
    private int KNOWN_QUAL_PRIOR = 9;
    @Argument(fullName="novel_prior", shortName="novelPrior", doc="A prior on the quality of novel variants, a phred scaled probability of being true.", required=false)
    private int NOVEL_QUAL_PRIOR = 2;
    @Argument(fullName="quality_scale_factor", shortName="qScale", doc="Multiply all final quality scores by this value. Needed to normalize the quality scores.", required=false)
    private double QUALITY_SCALE_FACTOR = 50.0;
    @Argument(fullName="output_prefix", shortName="output", doc="The prefix added to output VCF file name and optimization curve pdf file name", required=false)
    private String OUTPUT_PREFIX = "optimizer";
    @Argument(fullName="clusterFile", shortName="clusterFile", doc="The output cluster file", required=true)
    private String CLUSTER_FILENAME = "optimizer.cluster";
    //@Argument(fullName = "optimization_model", shortName = "om", doc = "Optimization calculation model to employ -- GAUSSIAN_MIXTURE_MODEL is currently the default, while K_NEAREST_NEIGHBORS is also available for small callsets.", required = false)
    private VariantOptimizationModel.Model OPTIMIZATION_MODEL = VariantOptimizationModel.Model.GAUSSIAN_MIXTURE_MODEL;
    @Argument(fullName = "path_to_Rscript", shortName = "Rscript", doc = "The path to your implementation of Rscript. For Broad users this is probably /broad/tools/apps/R-2.6.0/bin/Rscript", required = false)
    private String PATH_TO_RSCRIPT = "/broad/tools/apps/R-2.6.0/bin/Rscript";
    @Argument(fullName = "path_to_resources", shortName = "resources", doc = "Path to resources folder holding the Sting R scripts.", required = false)
    private String PATH_TO_RESOURCES = "R/";

    /////////////////////////////
    // Private Member Variables
    /////////////////////////////
    private VariantGaussianMixtureModel theModel = null;
    private VCFWriter vcfWriter;
    private Set<String> ignoreInputFilterSet = null;
    private final ArrayList<String> ALLOWED_FORMAT_FIELDS = new ArrayList<String>();

    //---------------------------------------------------------------------------------------------------------------
    //
    // initialize
    //
    //---------------------------------------------------------------------------------------------------------------

    public void initialize() {
        if( !PATH_TO_RESOURCES.endsWith("/") ) { PATH_TO_RESOURCES = PATH_TO_RESOURCES + "/"; }

        if( IGNORE_INPUT_FILTERS != null ) {
            ignoreInputFilterSet = new TreeSet<String>(Arrays.asList(IGNORE_INPUT_FILTERS));
        }

        switch (OPTIMIZATION_MODEL) {
            case GAUSSIAN_MIXTURE_MODEL:
                theModel = new VariantGaussianMixtureModel( TARGET_TITV, CLUSTER_FILENAME, BACKOFF_FACTOR );
                break;
            //case K_NEAREST_NEIGHBORS:
            //    theModel = new VariantNearestNeighborsModel( dataManager, TARGET_TITV, NUM_KNN );
            //    break;
            default:
                throw new StingException( "Variant Optimization Model is unrecognized. Implemented options are GAUSSIAN_MIXTURE_MODEL and K_NEAREST_NEIGHBORS" );
        }

        ALLOWED_FORMAT_FIELDS.add(VCFGenotypeRecord.GENOTYPE_KEY); // copied from VariantsToVCF
        ALLOWED_FORMAT_FIELDS.add(VCFGenotypeRecord.GENOTYPE_QUALITY_KEY);
        ALLOWED_FORMAT_FIELDS.add(VCFGenotypeRecord.DEPTH_KEY);
        ALLOWED_FORMAT_FIELDS.add(VCFGenotypeRecord.GENOTYPE_LIKELIHOODS_KEY);

        // setup the header fields
        final Set<VCFHeaderLine> hInfo = new HashSet<VCFHeaderLine>();
        hInfo.addAll(VCFUtils.getHeaderFields(getToolkit()));
        hInfo.add(new VCFInfoHeaderLine("OQ", 1, VCFInfoHeaderLine.INFO_TYPE.Float, "The original variant quality score"));
        hInfo.add(new VCFHeaderLine("source", "VariantOptimizer"));
        vcfWriter = new VCFWriter( new File(OUTPUT_PREFIX + ".vcf") );
        final TreeSet<String> samples = new TreeSet<String>();
        final List<ReferenceOrderedDataSource> dataSources = this.getToolkit().getRodDataSources();
        for( final ReferenceOrderedDataSource source : dataSources ) {
            final RMDTrack rod = source.getReferenceOrderedData();
            if( rod.getRecordType().equals(VCFRecord.class) ) {
                final VCFReader reader = new VCFReader(rod.getFile());
                final Set<String> vcfSamples = reader.getHeader().getGenotypeSamples();
                samples.addAll(vcfSamples);
                reader.close();
            }
        }
        final VCFHeader vcfHeader = new VCFHeader(hInfo, samples);
        vcfWriter.writeHeader(vcfHeader);

        boolean foundDBSNP = false;
        for( final ReferenceOrderedDataSource source : dataSources ) {
            final RMDTrack rod = source.getReferenceOrderedData();
            if( rod.getName().equals(DbSNPHelper.STANDARD_DBSNP_TRACK_NAME) ) {
                foundDBSNP = true;
            }
        }

        if(!foundDBSNP) {
            throw new StingException("dbSNP track is required. This calculation is critically dependent on being able to distinguish known and novel sites.");
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // map
    //
    //---------------------------------------------------------------------------------------------------------------

    public ExpandingArrayList<VariantDatum> map( RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context ) {
        final ExpandingArrayList<VariantDatum> mapList = new ExpandingArrayList<VariantDatum>();

        if( tracker == null ) { // For some reason RodWalkers get map calls with null trackers
            return mapList;
        }

        for( final VariantContext vc : tracker.getAllVariantContexts(ref, null, context.getLocation(), false, false) ) {
            if( vc != null && !vc.getName().equals(DbSNPHelper.STANDARD_DBSNP_TRACK_NAME) && vc.isSNP() ) {
                final VCFRecord vcf = VariantContextAdaptors.toVCF(vc, ref.getBase(), ALLOWED_FORMAT_FIELDS, false, false);
                if( !vc.isFiltered() || IGNORE_ALL_INPUT_FILTERS || (ignoreInputFilterSet != null && ignoreInputFilterSet.containsAll(vc.getFilters())) ) {
                    final VariantDatum variantDatum = new VariantDatum();
                    variantDatum.isTransition = vc.getSNPSubstitutionType().compareTo(BaseUtils.BaseSubstitutionType.TRANSITION) == 0;

                    final DbSNPFeature dbsnp = DbSNPHelper.getFirstRealSNP(tracker.getReferenceMetaData(DbSNPHelper.STANDARD_DBSNP_TRACK_NAME));
                    variantDatum.isKnown = dbsnp != null;
                    variantDatum.alleleCount = vc.getChromosomeCount(vc.getAlternateAllele(0)); // BUGBUG: assumes file has genotypes

                    final double acPrior =  theModel.getAlleleCountPrior( variantDatum.alleleCount );
                    final double knownPrior = ( variantDatum.isKnown ? QualityUtils.qualToProb(KNOWN_QUAL_PRIOR) : QualityUtils.qualToProb(NOVEL_QUAL_PRIOR) );                        
                    final double pTrue = theModel.evaluateVariant( vc ) * acPrior * knownPrior;

                    variantDatum.qual = QUALITY_SCALE_FACTOR * QualityUtils.phredScaleErrorRate( Math.max(1.0 - pTrue, 0.000000001) ); // BUGBUG: don't have a normalizing constant, so need to scale up qual scores arbitrarily
                    mapList.add( variantDatum );

                    vcf.addInfoField("OQ", ((Double)vc.getPhredScaledQual()).toString() );
                    vcf.setQual( variantDatum.qual );
                    vcf.setFilterString(VCFRecord.PASSES_FILTERS);
                    vcfWriter.addRecord( vcf );

                } else { // not a SNP or is filtered so just dump it out to the VCF file
                    vcfWriter.addRecord( vcf );
                }
            }

        }
        
        return mapList;
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // reduce
    //
    //---------------------------------------------------------------------------------------------------------------

     public ExpandingArrayList<VariantDatum> reduceInit() {
        return new ExpandingArrayList<VariantDatum>();
    }

    public ExpandingArrayList<VariantDatum> reduce( final ExpandingArrayList<VariantDatum> mapValue, final ExpandingArrayList<VariantDatum> reduceSum ) {
        reduceSum.addAll( mapValue );
        return reduceSum;
    }

    public void onTraversalDone( ExpandingArrayList<VariantDatum> reduceSum ) {

        vcfWriter.close();
        
        final VariantDataManager dataManager = new VariantDataManager( reduceSum, theModel.dataManager.annotationKeys );
        reduceSum.clear(); // Don't need this ever again, clean up some memory

        theModel.outputOptimizationCurve( dataManager.data, OUTPUT_PREFIX, DESIRED_NUM_VARIANTS );

        // Execute Rscript command to plot the optimization curve
        // Print out the command line to make it clear to the user what is being executed and how one might modify it
        final String rScriptCommandLine = PATH_TO_RSCRIPT + " " + PATH_TO_RESOURCES + "plot_OptimizationCurve.R" + " " + OUTPUT_PREFIX + ".dat" + " " + TARGET_TITV;
        System.out.println( rScriptCommandLine );

        // Execute the RScript command to plot the table of truth values
        try {
            Runtime.getRuntime().exec( rScriptCommandLine );
        } catch ( IOException e ) {
            throw new StingException( "Unable to execute RScript command: " + rScriptCommandLine );
        }
    }
}
