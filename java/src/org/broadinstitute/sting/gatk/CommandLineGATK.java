package org.broadinstitute.sting.gatk;

import org.broadinstitute.sting.gatk.walkers.Walker;
import org.broadinstitute.sting.utils.StingException;
import org.broadinstitute.sting.utils.xReadLines;
import org.broadinstitute.sting.utils.cmdLine.*;

import java.io.FileNotFoundException;
import java.io.File;
import java.util.List;
import java.util.ArrayList;

import net.sf.samtools.SAMFileReader;

/**
 *
 * User: aaron
 * Date: May 8, 2009
 * Time: 10:50:58 AM
 *
 * The Broad Institute
 * SOFTWARE COPYRIGHT NOTICE AGREEMENT 
 * This software and its documentation are copyright 2009 by the
 * Broad Institute/Massachusetts Institute of Technology. All rights are reserved.
 *
 * This software is supplied without any warranty or guaranteed support whatsoever. Neither
 * the Broad Institute nor MIT can be responsible for its use, misuse, or functionality.
 *
 */


/**
 * @author aaron
 * @version 1.0
 * @date May 8, 2009
 * <p/>
 * Class CommandLineGATK
 * <p/>
 * We run command line GATK programs using this class.  It gets the command line args, parses them, and hands the
 * gatk all the parsed out information.  Pretty much anything dealing with the underlying system should go here,
 * the gatk engine should  deal with any data related information.
 */
public class CommandLineGATK extends CommandLineProgram {

    @Argument(fullName = "analysis_type", shortName = "T", doc = "Type of analysis to run")
    public String analysisName = null;

    @ArgumentCollection // our argument collection, the collection of command line args we accept
    public GATKArgumentCollection argCollection = new GATKArgumentCollection();

    /** our genome analysis engine */
    GenomeAnalysisEngine GATKEngine = new GenomeAnalysisEngine();

    /** Required main method implementation. */
    public static void main(String[] argv) {
        try {
            CommandLineGATK instance = new CommandLineGATK();
            start(instance, argv);
        } catch (Exception e) {
            exitSystemWithError(e);
        }
    }

    /**
     * this is the function that the inheriting class can expect to have called
     * when the command line system has initialized.
     *
     * @return the return code to exit the program with
     */
    protected int execute() {
        Walker<?,?> mWalker = GATKEngine.getWalkerByName(analysisName);

        loadArgumentsIntoObject(argCollection);
        loadArgumentsIntoObject(mWalker);

        processArguments(argCollection);

        this.argCollection.analysisName = this.analysisName;
        try {
            GATKEngine.execute(argCollection, mWalker);
        }
        catch (ArgumentException ex) {
            // Rethrow argument exceptions.  Let the command-line argument do what it's designed to do.
            throw ex;
        }
        catch (StingException exp) {
            System.err.println("Caught StingException. It's message is " + exp.getMessage());
            exp.printStackTrace();
            return -1;
        }
        return 0;
    }

    /**
     * GATK can add arguments dynamically based on analysis type.
     *
     * @return true
     */
    @Override
    protected boolean canAddArgumentsDynamically() {
        return true;
    }

    /**
     * GATK provides the walker as an argument source.  As a side-effect, initializes the walker variable.
     *
     * @return List of walkers to load dynamically.
     */
    @Override
    protected Class[] getArgumentSources() {
        // No walker info?  No plugins.
        if (analysisName == null) return new Class[] {};
        return new Class[] { GATKEngine.getWalkerByName(analysisName).getClass() };
    }

    @Override
    protected String getArgumentSourceName(Class argumentSource) {
        return WalkerManager.getWalkerName((Class<Walker>) argumentSource);
    }

    public GATKArgumentCollection getArgCollection() {
        return argCollection;
    }

    public void setArgCollection(GATKArgumentCollection argCollection) {
        this.argCollection = argCollection;
    }

    /**
     * Get a custom factory for instantiating specialty GATK arguments.
     * @return An instance of the command-line argument of the specified type.
     */
    @Override
    protected ArgumentFactory getCustomArgumentFactory() {
        return new ArgumentFactory() {
            public Object createArgument( Class type, String repr ) {
                if (type == SAMFileReader.class) {
                    SAMFileReader samFileReader = new SAMFileReader(new File(repr),true);
                    samFileReader.setValidationStringency(argCollection.strictnessLevel);
                    return samFileReader;
                }
                return null;
            }
        };
    }


    /**
     * Preprocess the arguments before submitting them to the GATK engine.
     * @param argCollection Collection of arguments to preprocess.
     */
    private void processArguments( GATKArgumentCollection argCollection ) {
        argCollection.samFiles = unpackReads( argCollection.samFiles );    
    }

    /**
     * Unpack the files to be processed, given a list of files.  That list of files can
     * itself contain lists of other files to be read.
     * @param inputFiles
     * @return
     */
    private List<File> unpackReads( List<File> inputFiles ) {
        List<File> unpackedReads = new ArrayList<File>();
        for( File inputFile: inputFiles ) {
            if( inputFile.getName().endsWith(".list") ) {
                try {
                    for( String fileName : new xReadLines(inputFile) )
                        unpackedReads.add( new File(fileName) );
                }
                catch( FileNotFoundException ex ) {
                    throw new StingException("Unable to find file while unpacking reads", ex);
                }
            }
            else
                unpackedReads.add( inputFile );
        }
        return unpackedReads;
    }


}
