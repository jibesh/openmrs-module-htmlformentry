package org.openmrs.module.htmlformentry.web.controller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Encounter;
import org.openmrs.Form;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.htmlformentry.BadFormDesignException;
import org.openmrs.module.htmlformentry.FormEntryContext.Mode;
import org.openmrs.module.htmlformentry.FormEntrySession;
import org.openmrs.module.htmlformentry.FormSubmissionError;
import org.openmrs.module.htmlformentry.HtmlForm;
import org.openmrs.module.htmlformentry.HtmlFormEntryUtil;
import org.openmrs.module.htmlformentry.ValidationException;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The controller for entering/viewing a form.
 * <p/>
 * Handles {@code htmlFormEntry.form} requests. Renders view {@code htmlFormEntry.jsp}.
 * <p/>
 * TODO: This has a bit too much logic in the onSubmit method. Move that into the FormEntrySession.
 */
@Controller
public class HtmlFormEntryController {
    
    protected final Log log = LogFactory.getLog(getClass());
    public final static String closeDialogView = "/module/htmlformentry/closeDialog";
    public final static String FORM_IN_PROGRESS_KEY = "HTML_FORM_IN_PROGRESS_KEY";
    public final static String FORM_IN_PROGRESS_VALUE = "HTML_FORM_IN_PROGRESS_VALUE";
    public final static String FORM_PATH = "/module/htmlformentry/htmlFormEntry";
    public HttpServletResponse response;
    @RequestMapping(method=RequestMethod.GET, value=FORM_PATH)
    public void showForm() {
    	// Intentionally blank. All work is done in the getFormEntrySession method 
    }
    
    @ModelAttribute("command")
    public FormEntrySession getFormEntrySession(HttpServletRequest request,
                                                // @RequestParam doesn't pick up query parameters (in the url) in a POST, so I'm handling encounterId, modeParam, and which specially
                                                /*@RequestParam(value="mode", required=false) String modeParam,*/
                                                /*@RequestParam(value="encounterId", required=false) Integer encounterId,*/
                                                /*@RequestParam(value="which", required=false) String which,*/
                                                @RequestParam(value="patientId", required=false) Integer patientId,
                                                /*@RequestParam(value="personId", required=false) Integer personId,*/
                                                @RequestParam(value="formId", required=false) Integer formId,
                                                @RequestParam(value="htmlformId", required=false) Integer htmlFormId,
                                                @RequestParam(value="returnUrl", required=false) String returnUrl,
                                                @RequestParam(value="formModifiedTimestamp", required=false) Long formModifiedTimestamp,
                                                @RequestParam(value="encounterModifiedTimestamp", required=false) Long encounterModifiedTimestamp) throws Exception {

    	long ts = System.currentTimeMillis();

        Mode mode = Mode.VIEW;
    	
    	Integer personId = null;
    	
    	if (StringUtils.hasText(request.getParameter("personId"))) {
    		personId = Integer.valueOf(request.getParameter("personId"));
    	}
    	
    	
    	String modeParam = request.getParameter("mode");
		if ("enter".equalsIgnoreCase(modeParam)) {
			mode = Mode.ENTER;
		}
		else if ("edit".equalsIgnoreCase(modeParam)) {
            mode = Mode.EDIT;            
		}

        Patient patient = null;
    	Encounter encounter = null;
    	Form form = null;
    	HtmlForm htmlForm = null;

    	if (StringUtils.hasText(request.getParameter("encounterId"))) {
    		
    		Integer encounterId = Integer.valueOf(request.getParameter("encounterId"));
    		encounter = Context.getEncounterService().getEncounter(encounterId);
    		if (encounter == null)
    			throw new IllegalArgumentException("No encounter with id=" + encounterId);
    		patient = encounter.getPatient();
    		patientId = patient.getPatientId();
            personId = patient.getPersonId();
            
            if (formId != null) { // I think formId is allowed to differ from encounter.form.id because of HtmlFormFlowsheet
                form = Context.getFormService().getForm(formId);
                htmlForm = HtmlFormEntryUtil.getService().getHtmlFormByForm(form);
                if (htmlForm == null)
            		throw new IllegalArgumentException("No HtmlForm associated with formId " + formId);
            } else {
            	form = encounter.getForm();
                htmlForm = HtmlFormEntryUtil.getService().getHtmlFormByForm(encounter.getForm());
                if (htmlForm == null)
            		throw new IllegalArgumentException("The form for the specified encounter (" + encounter.getForm() + ") does not have an HtmlForm associated with it");
            }

    	} else { // no encounter specified

    		// get person from patientId/personId (register module uses patientId, htmlformentry uses personId)
			if (patientId != null) {
				personId = patientId;
			}
			if (personId != null) {
				patient = Context.getPatientService().getPatient(personId);
			}
			
			// determine form
			if (htmlFormId != null) {
	        	htmlForm = HtmlFormEntryUtil.getService().getHtmlForm(htmlFormId);
	        } else if (formId != null) {
	        	form = Context.getFormService().getForm(formId);
	        	htmlForm = HtmlFormEntryUtil.getService().getHtmlFormByForm(form);
	        }
	        if (htmlForm == null) {
	        	throw new IllegalArgumentException("You must specify either an htmlFormId or a formId for a valid html form");
	        }
			
			String which = request.getParameter("which");
			if (StringUtils.hasText(which)) {
	    		if (patient == null)
	    			throw new IllegalArgumentException("Cannot specify 'which' without specifying a person/patient");
	    		List<Encounter> encs = Context.getEncounterService().getEncounters(patient, null, null, null, Collections.singleton(form), null, null, false);
	    		if (which.equals("first")) {
	    			encounter = encs.get(0);
	    		} else if (which.equals("last")) {
	    			encounter = encs.get(encs.size() - 1);
	    		} else {
	    			throw new IllegalArgumentException("which must be 'first' or 'last'");
	    		}
	    	}
    	}
    	
		if (mode != Mode.ENTER && patient == null)
			throw new IllegalArgumentException("No patient with id of personId=" + personId + " or patientId=" + patientId);
                
        FormEntrySession session = null;
		if (mode == Mode.ENTER && patient == null) {
			patient = new Patient();			
		}
		if (encounter != null) {
			session = new FormEntrySession(patient, encounter, mode, htmlForm);				
		} 
		else {
			session = new FormEntrySession(patient, htmlForm);
		}

        if (StringUtils.hasText(returnUrl)) {
            session.setReturnUrl(returnUrl);
        }

        // Since we're not using a sessionForm, we need to check for the case where the underlying form was modified while a user was filling a form out
        if (formModifiedTimestamp != null) {
            if (!OpenmrsUtil.nullSafeEquals(formModifiedTimestamp, session.getFormModifiedTimestamp())) {
                throw new RuntimeException(Context.getMessageSourceService().getMessage("htmlformentry.error.formModifiedBeforeSubmission"));
            }
        }

        // Since we're not using a sessionForm, we need to make sure this encounter hasn't been modified since the user opened it
        if (encounter != null) {
        	if (encounterModifiedTimestamp != null && !OpenmrsUtil.nullSafeEquals(encounterModifiedTimestamp, session.getEncounterModifiedTimestamp())) {
        		throw new RuntimeException(Context.getMessageSourceService().getMessage("htmlformentry.error.encounterModifiedBeforeSubmission"));
        	}
        }
        
        Context.setVolatileUserData(FORM_IN_PROGRESS_KEY, session);
       
        log.info("Took " + (System.currentTimeMillis() - ts) + " ms");
        
        return session;
    }
    
    /*
     * I'm using a return type of ModelAndView so I can use RedirectView rather than "redirect:" and preserve the fact that
     * returnUrl values from the pre-annotated-controller days will have the context path already
     */
    @RequestMapping(method=RequestMethod.POST, value=FORM_PATH)
    public ModelAndView handleSubmit(@ModelAttribute("command") FormEntrySession session,
                               Errors errors,@RequestParam(value = "upldWidget", required = false) MultipartFile file[] ,
                               @RequestParam(value = "nullObsIds",required = false) String nullObsIds,
                               HttpServletRequest request,
                               Model model) throws Exception {
        HashMap fileNames = new HashMap();
        HashMap complexObs=new HashMap();
        /* Extracting only the complexObs into a HashMap */
        if(session.getContext().getMode().toString().equalsIgnoreCase("EDIT"))
        {
           Set<Obs> allObs= session.getEncounter().getAllObs();
           Iterator iterator=allObs.iterator();
            int i=0;
            while (iterator.hasNext()){
                Obs xy=(Obs) iterator.next();
                if(xy.isComplex()) {
                    complexObs.put(i,xy);
                    i++;
                }
            }
        }

        try {
            if (request instanceof MultipartHttpServletRequest && file!=null) {
                for (int i = 0; i < file.length; i++) {
                    /* If the Mode is "EDIT" mode and file is empty then there  are two possibilities.
                    * 1. The file has to be deleted
                    * 2. The file has to retained */
                    if (session.getContext().getMode().toString().equalsIgnoreCase("EDIT") && file[i].isEmpty() && i<complexObs.size()) {

                        fileNames.put(i, null);
                        for (int k=0;k<complexObs.size();k++) {
                            Obs retainingObs = (Obs) complexObs.get(i);

                           /* If the Id of Obs is not among the ids that has to deleted then retain the name of the complexObs */
                           if(!nullObsIds.contains(retainingObs.getId().toString())){
                              fileNames.put(i, retainingObs.getValueComplex());
                           }
                        }

                    }else if (file[i].isEmpty()) /* In all other cases when file is empty, means nothing is being uploaded */
                             fileNames.put(i, null);
                    if (!file[i].isEmpty()) {
                        AdministrationService as = Context.getAdministrationService();

                        File complexObsDir = OpenmrsUtil.getDirectoryInApplicationDataDirectory(as.getGlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_COMPLEX_OBS_DIR));
                        if (complexObsDir.exists() && complexObsDir.canWrite()) {
                            String pathtostoreFile = complexObsDir.toPath().resolve(file[i].getOriginalFilename()).toString();
                            FileOutputStream fileOutputStream = new FileOutputStream(pathtostoreFile);
                            byte[] bytes = file[i].getBytes();
                            fileOutputStream.write(bytes);
                            fileOutputStream.close();
                            fileNames.put(i, file[i].getOriginalFilename());
                        }
                        log.info("File Uploaded successfully");
                    } else {
                        log.info("File not Uploaded");
                    }
                    request.setAttribute("upldWidget", fileNames);
                }
            }
        } catch (Exception exception) {
            log.error("Exception during File Upload", exception);
            errors.reject("Exception during File Upload, see log for further details: " + exception);
        }
        try {
            List<FormSubmissionError> validationErrors = session.getSubmissionController().validateSubmission(session.getContext(), request);
            if (validationErrors != null && validationErrors.size() > 0) {
                errors.reject("Fix errors");
            }
        } catch (Exception ex) {
            log.error("Exception during form validation", ex);
            errors.reject("Exception during form validation, see log for more details: " + ex);
        }

        if (errors.hasErrors()) {
        	return new ModelAndView(FORM_PATH, "command", session);
        }

        // no form validation errors, proceed with submission

        session.prepareForSubmit();

		if (session.getContext().getMode() == Mode.ENTER && session.hasPatientTag() && session.getPatient() == null
				&& (session.getSubmissionActions().getPersonsToCreate() == null || session.getSubmissionActions().getPersonsToCreate().size() == 0))
			throw new IllegalArgumentException("This form is not going to create an Patient");

        if (session.getContext().getMode() == Mode.ENTER && session.hasEncouterTag() && (session.getSubmissionActions().getEncountersToCreate() == null || session.getSubmissionActions().getEncountersToCreate().size() == 0))
            throw new IllegalArgumentException("This form is not going to create an encounter");

    	try {
            session.getSubmissionController().handleFormSubmission(session, request);
            session.applyActions();
            String successView = session.getReturnUrlWithParameters();
            if (successView == null)
                successView = request.getContextPath() + "/patientDashboard.form" + getQueryPrameters(request, session);
            if (StringUtils.hasText(request.getParameter("closeAfterSubmission"))) {
            	return new ModelAndView(closeDialogView, "dialogToClose", request.getParameter("closeAfterSubmission"));
            } else {
            	return new ModelAndView(new RedirectView(successView));
            }
        } catch (ValidationException ex) {
            log.error("Invalid input:", ex);
            errors.reject(ex.getMessage());
        } catch (BadFormDesignException ex) {
            log.error("Bad Form Design:", ex);
            errors.reject(ex.getMessage());
        } catch (Exception ex) {
            log.error("Exception trying to submit form", ex);
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            errors.reject("Exception! " + ex.getMessage() + "<br/>" + sw.toString());
        }

        // if we get here it's because we caught an error trying to submit/apply
        return new ModelAndView(FORM_PATH, "command", session);
    }

	protected String getQueryPrameters(HttpServletRequest request, FormEntrySession formEntrySession) {
		return "?patientId=" + formEntrySession.getPatient().getPersonId();
	}
}
