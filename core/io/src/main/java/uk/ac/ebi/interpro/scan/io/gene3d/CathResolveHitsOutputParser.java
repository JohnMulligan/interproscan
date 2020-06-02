package uk.ac.ebi.interpro.scan.io.gene3d;

import uk.ac.ebi.interpro.scan.io.match.hmmer.hmmer3.parsemodel.DomTblDomainMatch;
import uk.ac.ebi.interpro.scan.util.Utilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Parser for CathResolveHitsoutput ....
 */
public class CathResolveHitsOutputParser {

    private static final org.apache.log4j.Logger LOGGER = org.apache.log4j.Logger.getLogger(CathResolveHitsOutputParser.class.getName());

    /**
     *
     * @param is
     * @return
     * @throws IOException
     */

    public Map<String, Set<CathResolverRecord>> parse(InputStream is) throws IOException {
        //We may have discontinuous domains having the same cathRecord.getRecordKey() whichis the one in the domtblout
        // so we keep the matches in a set, slightly expensive
        Map<String, Set<CathResolverRecord>> cathResolverRecordMap = new HashMap<>();
        BufferedReader reader = null;
        int rawDomainCount = 0;
        int recordCount = 0;
        try {
            reader = new BufferedReader(new InputStreamReader(is));
            int lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty() || line.startsWith("#")){
                    continue;
                }
                rawDomainCount ++;
                // Look for a domain data line.
                CathResolverRecord cathRecord = CathResolverRecord.valueOf(line);

                if (cathRecord == null) {
                    LOGGER.error("Bad cathRecord, cathRecord is null line is : " + line);
                    throw new IllegalStateException("Failed to create cathRecord for line  " + line);
                } else {
                    String key = cathRecord.getRecordKey();
                    Set<CathResolverRecord> cathRecordSet = cathResolverRecordMap.get(key);
                    if (cathRecordSet == null) {
                        cathRecordSet = new HashSet<>();
                        cathResolverRecordMap.put(key, cathRecordSet);
                    }
                    cathRecordSet.add(cathRecord);
                    recordCount ++;
                }
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        Utilities.verboseLog(110, "CathResolverRecord set count (distinct keys) : " + cathResolverRecordMap.values().size());
        Utilities.verboseLog(110, "CathResolverRecord count : " + recordCount);
        LOGGER.debug(" domtbl domain count : " + cathResolverRecordMap.values().size());

        return cathResolverRecordMap;
    }
}
