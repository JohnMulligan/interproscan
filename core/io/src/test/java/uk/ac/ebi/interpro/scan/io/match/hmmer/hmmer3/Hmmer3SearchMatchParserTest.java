package uk.ac.ebi.interpro.scan.io.match.hmmer.hmmer3;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import uk.ac.ebi.interpro.scan.model.raw.Gene3dHmmer3RawMatch;
import uk.ac.ebi.interpro.scan.model.raw.Hmmer3RawMatch;
import uk.ac.ebi.interpro.scan.model.raw.RawProtein;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Hmmer3SearchMatchParser}
 *
 * @author Phil Jones
 * @author Antony Quinn
 * @version $Id$
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration
public class Hmmer3SearchMatchParserTest {

    private static final Logger LOGGER = LogManager.getLogger(Hmmer3SearchMatchParserTest.class.getName());

    // Pfam
    @Resource
    private Hmmer3SearchMatchParser<Hmmer3RawMatch> pfamParser;
    @Resource
    private org.springframework.core.io.Resource pfamFile;

    // Gene3D
    @Resource
    private Hmmer3SearchMatchParser<Gene3dHmmer3RawMatch> gene3dParser;
    @Resource
    private org.springframework.core.io.Resource gene3dFile;

    // Tests two matches at same location (see IBU-1133)
    @Resource
    private org.springframework.core.io.Resource highestScoringDomainFile;

    // Tests empty alignment lines
    @Resource
    private org.springframework.core.io.Resource emptyAlignmentLineFile;

    @Test
    @Disabled // As of Gene3d 4.2.0, no longer applies
    public void testGene3DParser() throws IOException {
        final Set<String> expected = new HashSet<String>(Arrays.asList(
                "HP0834:24M2I9M1D9M1D2M2D10M7I42M7D16M5D12M1I24M",
                "HP0834:29M1I17M1D2M2D10M9I5M1I22M2D13M2D1M3D17M3D12M1I1M4I23M",
                "NT01CJ0385:24M2I9M1D9M3D26M7I14M1D15M6D16M4D7M1D4M1I22M",
                "NT01CJ0385:29M1D17M1D14M9I5M1I18M2D17M2D1M3D17M3I39M"
        ));
        final Set<String> actual = new HashSet<String>();
        Set<RawProtein<Gene3dHmmer3RawMatch>> proteins = parse(gene3dParser, gene3dFile.getInputStream());
        for (RawProtein<Gene3dHmmer3RawMatch> p : proteins) {
            for (Gene3dHmmer3RawMatch m : p.getMatches()) {
                actual.add(m.getSequenceIdentifier() + ":" + m.getAlignedRegions());
            }
        }
        assertEquals( expected, actual, "Expected matches not found");
    }

    @Test
    public void testHighestScoringDomain() throws IOException {
        final String SEP = ":";
        final Set<String> expected = new HashSet<String>(Arrays.asList(
                "UPI0000054B90" + SEP +
                        "564" + SEP +           // start
                        "615" + SEP +           // end
                        "21.5"                  // score
        ));
        final Set<String> actual = new HashSet<String>();
        Set<RawProtein<Gene3dHmmer3RawMatch>> proteins = parse(gene3dParser, highestScoringDomainFile.getInputStream());
        for (RawProtein<Gene3dHmmer3RawMatch> p : proteins) {
            for (Gene3dHmmer3RawMatch m : p.getMatches()) {
                actual.add(m.getSequenceIdentifier() + SEP +
                        m.getLocationStart() + SEP +
                        m.getLocationEnd() + SEP +
                        m.getLocationScore());
            }
        }
        assertEquals( expected, actual, "Expected matches not found");
    }

    @Test
    @Disabled // As of Gene3d 4.2.0, no longer applies
    public void testEmptyAlignmentLine() throws IOException {

        final Set<String> expected = new HashSet<String>(Arrays.asList(
                "UPI0000005B9B:33M6I32M45I45M153D2M30I28M"
        ));

        final Set<String> actual = new HashSet<String>();
        Set<RawProtein<Gene3dHmmer3RawMatch>> proteins = parse(gene3dParser, emptyAlignmentLineFile.getInputStream());
        for (RawProtein<Gene3dHmmer3RawMatch> p : proteins) {
            for (Gene3dHmmer3RawMatch m : p.getMatches()) {
                actual.add(m.getSequenceIdentifier() + ":" + m.getAlignedRegions());
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(m.getSequenceIdentifier() + ":" + m.getAlignedRegions());
                }
            }
        }

        assertEquals(expected, actual, "Expected alignments not found");
    }

    @Test
    @Disabled("Currently need to have an entire hmm library in the classpath, so this needs to be switched on manually.  Note that the location / name of the hmm linbrary and the hmm results file should be set in the test context.xml file src/test/resources/uk/ac/ebi/interpro/scan/io/match/hmmer/hmmer3/Hmmer3SearchMatchParserTest-context.xml")
    public void testPfamParser() throws IOException {
        parse(pfamParser, pfamFile.getInputStream());
    }

    private <T extends Hmmer3RawMatch> Set<RawProtein<T>> parse(Hmmer3SearchMatchParser<T> parser,
                                                                InputStream is)
            throws IOException {
        Set<RawProtein<T>> proteins = null;
        try {
            proteins = parser.parse(is);
            assertTrue( proteins.size() > 0, "Must be at least one protein in collection");
        }
        finally {
            is.close();
        }
        return proteins;
    }

}
