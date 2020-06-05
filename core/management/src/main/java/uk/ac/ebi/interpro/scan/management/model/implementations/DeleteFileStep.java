package uk.ac.ebi.interpro.scan.management.model.implementations;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Required;
import uk.ac.ebi.interpro.scan.management.model.Step;
import uk.ac.ebi.interpro.scan.management.model.StepInstance;
import uk.ac.ebi.interpro.scan.util.Utilities;

import java.io.File;

/**
 * Simply deletes a File located at the path provided.
 *
 * @author Phil Jones
 * @version $Id$
 * @since 1.0
 */
public class DeleteFileStep extends Step {

    private static final Logger LOGGER = LogManager.getLogger(DeleteFileStep.class.getName());

    private String[] fileNameTemplate;

    private boolean deleteWorkingDirectoryOnCompletion;

    @Required
    public void setFileNameTemplate(String... filePathTemplate) {
        this.fileNameTemplate = filePathTemplate;
    }

    public void setDeleteWorkingDirectoryOnCompletion(boolean deleteWorkingDirectoryOnCompletion) {
        this.deleteWorkingDirectoryOnCompletion = deleteWorkingDirectoryOnCompletion;
    }

    /**
     * This method is called to execute the action that the StepInstance must perform.
     * <p/>
     * If an error occurs that cannot be immediately recovered from, the implementation
     * of this method MUST throw a suitable Exception, as the call
     * to execute is performed within a transaction with the reply to the JMSBroker.
     *
     * @param stepInstance           containing the parameters for executing.
     * @param temporaryFileDirectory
     * @throws Exception could be anything thrown by the execute method.
     */
    @Override
    public void execute(StepInstance stepInstance, String temporaryFileDirectory) {
        LOGGER.info("Starting step with Id " + this.getId());
        LOGGER.debug("deleteWorkingDirectoryOnCompletion: " + deleteWorkingDirectoryOnCompletion);

        //check if we need to skip this step for this range
        if (job.getLibraryRelease() != null && checkIfDoSkipRun(stepInstance.getBottomProtein(), stepInstance.getTopProtein())) {
            String key = getKey(stepInstance.getBottomProtein(), stepInstance.getTopProtein());
            Utilities.verboseLog(110, "doSkipRun - step: "  + this.getId() + " - " + key);
            return;
        }

        if(deleteWorkingDirectoryOnCompletion) {
            if (fileNameTemplate != null && fileNameTemplate.length > 0) {
                for (String fileName : fileNameTemplate) {
                    final String filePathName = stepInstance.buildFullyQualifiedFilePath(temporaryFileDirectory, fileName);
                    LOGGER.debug("Deleting file: " + fileName);
                    File file = new File(filePathName);
                    if (file.exists()) {
                        if (!file.delete()) {
                            LOGGER.error("Unable to delete the file located at " + filePathName);
                            throw new IllegalStateException("Unable to delete the file located at " + filePathName);
                        }
                    }else{
                        LOGGER.info("File not found, file located at " + filePathName);
                    }
                }
            }
            else {
                throw new IllegalStateException("Delete file step called without specifying any files to delete");
            }
        }else{
            LOGGER.debug("File delete step skipped -  delete.working.directory.on.completion =  " + deleteWorkingDirectoryOnCompletion);
        }
        LOGGER.info("Step with Id " + this.getId() + " finished.");
    }
}
