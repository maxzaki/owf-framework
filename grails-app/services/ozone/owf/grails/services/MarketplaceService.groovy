// OZP-476: MP Synchronization
// This class was added to ease the initial merge pain of adding this
// functionality to the WidgetDefinitionService, which is the primary user
// of this particular service. We'd also need to consider the
// MarketplaceController, which was introduced for similar reasons (avoiding
// merge issues). Entirely possible that we'd want to eliminate this class
// and fold its function back into the WidgetDefinitionService; similar may
// also apply to the controller class.
package ozone.owf.grails.services

import grails.converters.JSON

import java.security.KeyStore
import java.security.cert.X509Certificate

import org.apache.http.client.ClientProtocolException
import org.apache.http.client.HttpResponseException
import org.apache.http.client.methods.HttpGet
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.ssl.AllowAllHostnameVerifier
import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.conn.ssl.TrustStrategy
import org.apache.http.impl.client.BasicResponseHandler
import org.apache.http.impl.client.DefaultHttpClient
import org.codehaus.groovy.grails.commons.ConfigurationHolder

import ozone.owf.grails.domain.*
import org.springframework.context.ApplicationContext
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.codehaus.groovy.grails.commons.spring.GrailsApplicationContext
import org.springframework.context.ApplicationContextAware
import ozone.owf.grails.OwfException
import ozone.owf.grails.OwfExceptionTypes

class MarketplaceService extends BaseService {

    def config = ConfigurationHolder.config
    def domainMappingService
    def grailsApplication
    def accountService
    def stackService
    def widgetDefinitionService

    // Performs some of the function of addExternalWidgetsToUser, found in the
    // WidgetDefinitionService.
    def addListingsToDatabase(stMarketplaceJson) {
        // The set could be greater than one in length because widgets do have
        // dependencies.
        //log.info("addListingsToDatabase")
        def updatedWidgets=stMarketplaceJson.collect { obj ->
            if (obj?.widgetGuid) {
                return addWidgetToDatabase(obj)
            } else if (obj?.stackContext) {
                return stackService.importStack([ data: obj.toString() ])
            }
        }

        // Yes, re-reading the set.  We need to add requirements after all widgets have been added
        stMarketplaceJson.each { obj ->
            // Same comment as before: this only applies to an updated OMP baseline which
            // supports synchronization. Older baselines will have "obj" as an instance of
            // WidgetDefinition.
            if (obj instanceof Map && obj.containsKey("directRequired")) {
                // delete and the recreate requirements
                def widgetDefinition = WidgetDefinition.findByWidgetGuid(obj.widgetGuid, [cache:true])
                domainMappingService.deleteAllMappings(widgetDefinition, RelationshipType.requires, 'src')

                obj.directRequired.each {
                    if (log.isDebugEnabled()) {
                        log.debug "obj.directRequired.each.it -> ${it}"
                    }
                    def requiredWidget = WidgetDefinition.findByWidgetGuid(it, [cache:true])
                    if (requiredWidget != null) {
                        domainMappingService.createMapping(widgetDefinition, RelationshipType.requires, requiredWidget)
                    }
                }
            }
        }
        return updatedWidgets
    }

    def addWidgetToDatabase(obj) {
        def widgetDefinition = WidgetDefinition.findByWidgetGuid(obj.widgetGuid, [cache:true])
        boolean universalNameIsChanged = true

        if (widgetDefinition == null) {
            if (grails.util.GrailsUtil.environment != 'test') {
                log.info "Creating new widget definition for ${obj.widgetGuid}"
            }

            widgetDefinition=new WidgetDefinition()
        }
        else {
            universalNameIsChanged = obj.universalName && obj.universalName != widgetDefinition.universalName
        }

        widgetDefinition.displayName = obj.displayName
        widgetDefinition.description = obj.description
        widgetDefinition.height = obj.height as Integer
        widgetDefinition.imageUrlLarge = obj.imageUrlLarge
        widgetDefinition.imageUrlSmall = obj.imageUrlSmall

        if (universalNameIsChanged && !widgetDefinitionService.canUseUniversalName(widgetDefinition, obj.universalName)) {
            throw new OwfException(message: 'Another widget uses ' + obj.universalName + ' as its Universal Name. '
                    + 'Please select a unique Universal Name for this widget.', exceptionType: OwfExceptionTypes.Validation_UniqueConstraint)
        }
        widgetDefinition.universalName = obj.universalName

        widgetDefinition.widgetGuid = obj.widgetGuid
        widgetDefinition.widgetUrl = obj.widgetUrl
        widgetDefinition.widgetVersion = obj.widgetVersion
        widgetDefinition.width = obj.width as Integer
        widgetDefinition.singleton = obj.singleton
        widgetDefinition.visible = obj.widgetUrl.isAllWhitespace() ? false : obj.visible
        widgetDefinition.background = obj.background
        widgetDefinition.descriptorUrl = obj.descriptorUrl
        widgetDefinition.save(flush: true, failOnError: true)

        if (obj.widgetTypes) {
            // for each widget type T in the listing from MP, add the corresponding OWF widget
            // type to widgetTypes or add standard if there's no corresponding type.
            widgetDefinition.widgetTypes = []

            obj.widgetTypes.each { String widgetTypeFromMP ->
                def typeFound = WidgetType.findByName(widgetTypeFromMP)
                if (typeFound) {
                    widgetDefinition.widgetTypes << typeFound
                } else {
                    widgetDefinition.widgetTypes << WidgetType.standard
                }
            }
        } else {
            widgetDefinition.widgetTypes = [ WidgetType.standard ]
        }

        // Delete any existing tags.  Not a good bulk method for doing this, though
        // could possibly use the setTags() method.
        widgetDefinition.getTags().each { tagLinkRecord ->
            widgetDefinition.removeTag(tagLinkRecord.tag.name)
        }

        // It will be a JSONObject if we've fetched from a Marketplace. If we're
        // supporting the older OMP baseline, obj will be a WidgetDefinition object.
        obj.defaultTags?.each { tagName ->
                widgetDefinition.addTag(tagName, true, -1, true)
        }

        // Marketplace may not be configured to provide intents. In such
        // a case the send and receive lists are empty. DO NOT overwrite
        // intents that are already in OWF if MP is not providing them.
        if (obj.intents &&
            ((obj.intents.send && obj.intents.send.size() > 0) ||
             (obj.intents.receive && obj.intents.receive.size() > 0))) {
            // Structure of the intents field (forgive the bastardized BNF/schema mix)
            //   send: [ $intent ]
            //   receive: [ $intent ]
            //  where
            //   $intent => [ {action: $action, dataTypes: [$dataType]}]
            //  and $action and $dataType are strings

            // The post-conditions that seem to be needed to successfully add an intent to a WidgetDefinition
            // IntentDataType
            //    - Unique by the dataType field.
            //    - An expensive string.
            // Intent object
            //    - Unique by "action".
            //    - Relationships to ALL IntentDataType that any widget, anywhere has associated with that action.
            // WidgetDefinitionIntent object
            //    - Owned by the WidgetDefinition
            //    - Points to an Intent
            //    - Gets the action from the Intent, but has it's own collection of IntentDataType
            //    - Has send and receive flags, but they seem to be mutually exclusive.  Not sure if it's a hard
            //      constraint, but I can't find anyplace where one is built with both flags set the same way
            // WidgetDefinition
            //    - Has a collection of WidgetDefinitionIntent objects

            // Enjoy the ride...
            //

            // wipe the slate clean, this ain't no PATCH action
            WidgetDefinitionIntent.findAllByWidgetDefinition(widgetDefinition).collect() {
                def intent = it.intent
                intent.removeFromWidgetDefinitionIntents(it)
                widgetDefinition.removeFromWidgetDefinitionIntents(it)
                intent.save()
                widgetDefinition.save()
                it.delete()
            }
            // This helper takes a $intent and makes the Intent object consistent with it
            def addIntent={ intent,isSend,isReceive ->
                // We're going to need the IntentDataTypes for multiple actions, below
                def allIntentDataTypes = intent.dataTypes.collect() {
                    IntentDataType.findByDataType(it) ?: new IntentDataType(dataType: it)
                }
                // Patch together the Intent object
                def intentModel=Intent.findByAction(intent.action)
                if(!intentModel) {
                    // If it's a new one, we can just assign it all of the data types.
                    intentModel=new Intent(action: intent.action, dataTypes: allIntentDataTypes)
                } else {
                    // According to the GORM reference docs, a hasMany relationship is a set
                    // So this *should* eliminate duplicates.
                    intentModel.dataTypes.addAll(allIntentDataTypes)
                }
                // Shouldn't be needed, in theory, it's too much effort to figure out if
                // cascading saves is set up properly
                intentModel.save()

                // First two post conditions have been met, now for the actual WidgetDefinition
                // Since we cleared them out to start with, we don't have to
                def newWidgetDefinitionIntent = new WidgetDefinitionIntent(
                    widgetDefinition: widgetDefinition,
                    intent: intentModel,
                    send: isSend,
                    receive: isReceive,
                    dataTypes: allIntentDataTypes
                )
                widgetDefinition.addToWidgetDefinitionIntents(newWidgetDefinitionIntent)
            }

            // Now use the helper twice, once for send, once for receive
            obj.intents.receive?.each { addIntent(it,false,true) }
            obj.intents.send?.each { addIntent(it, true,false) }
        }

        widgetDefinition.save(flush:true)
        return widgetDefinition
    }

    def addExternalWidgetsToUser(params) {
        def mpSourceUrl = params.marketplaceUrl ?: "${grailsApplication.config.owf.marketplaceLocation}"
        def user = accountService.getLoggedInUser()
        def widgetDefinition = null
        def mapping = null
        def tagLinks = null
        def usedMpPath = false

        // OZP-476: MP Synchronization
        // Since we're allowing system-system synchronization (OMP -> OWF, OMP -> OMP) this
        // doesn't necessarily apply any more. We don't require a user to be an admin just
        // to update a listing from a well-known location.
        //
        //user = accountService.getLoggedInUser()
        //if (params.userId != null) {
        //    ensureAdmin()
        //    user = Person.findById(params.userId)
        //    if (user == null) {
        //        throw new OwfException(	message:'Invalid userId',
        //            exceptionType: OwfExceptionTypes.Validation)
        //    }
        //}

        //add widgets to db also add pwd mappings to current user
        def widgetDefinitions = []
        params.widgets = JSON.parse(params.widgets)
        params.widgets?.each {
            def obj = JSON.parse(it)
            if (obj.widgetGuid == null) {
                throw new OwfException( message:'WidgetGuid must be provided',
                    exceptionType: OwfExceptionTypes.Validation)
            }

            widgetDefinition = WidgetDefinition.findByWidgetGuid(obj.widgetGuid, [cache:true])
            if (widgetDefinition == null) {
                // OZP-476: MP Synchronization
                // The default is to fetch a widget from a well-known MP.  If we can't do that
                // or if the fetch fails, then fall back to the original behavior, which reads
                // the supplied JavaScript and creates a widget from that.
                def setWidgets = new HashSet()
                try {
                    log.debug("Widget not found locally, building from marketplace with guid=${obj.widgetGuid} and mpUrl=${mpSourceUrl}")
                    setWidgets.addAll(addListingsToDatabase(buildWidgetListFromMarketplace(obj.widgetGuid, mpSourceUrl)))
                    log.debug("Found ${setWidgets.size()} widgets ${setWidgets.collect {it.toString()}} from the ${mpSourceUrl}")
                    usedMpPath = true
                } catch (Exception e) {
                    log.error "addExternalWidgetsToUser: unable to build widget list from Marketplace, message -> ${e.getMessage()}", e
                }

                if (setWidgets.isEmpty()) {
                    // If set is empty, then call to MP failed.  Fallback path.
                    log.debug("Importing from the JSON provided to us, since marketplace failed")
                    setWidgets.addAll(addListingsToDatabase([obj]))
                }
                // OZP-476: MP Synchronization
                // See comments on the MarketplaceService regarding what functionality should/could
                // be moved back into this service.
                widgetDefinition = setWidgets.find {it.widgetGuid == obj.widgetGuid}
            }
            widgetDefinitions.push(widgetDefinition)

            def queryReturn = PersonWidgetDefinition.executeQuery("SELECT MAX(pwd.pwdPosition) AS retVal FROM PersonWidgetDefinition pwd WHERE pwd.person = ?", [user])
            def maxPosition = (queryReturn[0] != null)? queryReturn[0] : -1
            maxPosition++

            mapping = PersonWidgetDefinition.findByPersonAndWidgetDefinition(user, widgetDefinition)
            if (mapping == null && (obj.isSelected || !grailsApplication.config.owf.enablePendingApprovalWidgetTagGroup)) {
                mapping = new PersonWidgetDefinition(
                    person: user,
                    widgetDefinition: widgetDefinition,
                    visible : true,
                    disabled: grailsApplication.config.owf.enablePendingApprovalWidgetTagGroup,
                    pwdPosition: maxPosition
                )

                if (mapping.hasErrors()) {
                    throw new OwfException( message:'A fatal validation error occurred during the creation of the person widget definition. Params: ' + params.toString() + ' Validation Errors: ' + mapping.errors.toString(),
                        exceptionType: OwfExceptionTypes.Validation)
                }

                if (!mapping.save(flush: true)) {
                    throw new OwfException(
                        message: 'A fatal error occurred while trying to save the person widget definition. Params: ' + params.toString() +
                        "\n    on definition: " + obj.toString() + "\n    when trying to save mapping " + mapping.toString(),
                        exceptionType: OwfExceptionTypes.Database)
                }
                // OZP-476: MP Synchronization
                // The following block of code is functionally contained within the
                // MarketplaceService.addListingsToDatabase call.  However, to support
                // Marketplaces based on an older baseline, we bracket with the
                // usedMpPath.
                if (!usedMpPath) {
                    if (obj.tags) {
                        def tags = JSON.parse(obj.tags)
                        tags.each {
                            mapping.addTag(it.name, it.visible, it.position, it.editable)
                        }
                    }
                }
            }
        }

        // OZP-476: MP Synchronization
        // The following block of code is functionally contained within the
        // MarketplaceService.addListingsToDatabase call. As with the tags,
        // we support an older marketplace baseline by bracketing the call
        // with the usedMpPath.
        //
        // Add requirements after all widgets have been added
        if (!usedMpPath) {
            params.widgets?.each {
                def obj = JSON.parse(it)
                if (obj.directRequired != null) {
                    // delete and the recreate requirements
                    widgetDefinition = WidgetDefinition.findByWidgetGuid(obj.widgetGuid, [cache:true])
                    domainMappingService.deleteAllMappings(widgetDefinition, RelationshipType.requires, 'src')

                    def requiredArr = JSON.parse(obj.directRequired)
                    requiredArr.each {
                        def requiredWidget = WidgetDefinition.findByWidgetGuid(it, [cache:true])
                        if (requiredWidget != null) {
                            domainMappingService.createMapping(widgetDefinition, RelationshipType.requires, requiredWidget)
                        }
                    }
                }
            }
        }
        return [success: true, data: widgetDefinitions]
    }

    // We allow for widgets to mutually refer to one another, which would normally result in
    // a stack overflow using a recursive algorithm.  Hence the "seen" set below to guard
    // against re-processing.
    def buildWidgetListFromMarketplace(guid, def mpSourceUrl = null, def seen = new HashSet()) {
        def widgetJsonMarketplace = new HashSet()

        def obj = getObjectListingFromMarketplace(guid, mpSourceUrl)
        seen.add(guid)
        obj?.directRequired?.each {
            if (!seen.contains(it)) {
                widgetJsonMarketplace.addAll(buildWidgetListFromMarketplace(it, mpSourceUrl, seen))
            }
        }
        if (obj)
            widgetJsonMarketplace.add(obj)
        widgetJsonMarketplace
    }

    // FIXME: This code would be simpler with the Grails REST plugin.
    private getObjectListingFromMarketplace(guid, mpSourceUrl) {
        // In some scenarios, the MP url could be null (for example, save a service item listing
        // in marketplace triggering an update in OWF). In these cases, build a list of possible
        // marketplaces to check for the GUID.  Those can be found by looking in widget definitions
        // for marketplace type listings and getting the URLs.
        def setMpUrls = new HashSet()
        if (mpSourceUrl && config.owf.mpSync.trustProvidedUrl) {
            setMpUrls.add(mpSourceUrl)
        } else {
            WidgetType.findByName('marketplace').collect { tpe ->
                tpe.widgetDefinitions.each { wd ->
                    setMpUrls.add(wd.widgetUrl)
                }
            }
        }
        def ompObj

        def socketFactory = createSocketFactory();

        setMpUrls.find { mpUrl ->
            // Check each configured marketplace and stop when we get a match.
            def port
            try {
                def u = new URL(mpUrl)
                if (u.getPort() > -1) {
                    port = u.getPort()
                }
            } catch (Exception e) {}

            def client = new DefaultHttpClient()
            try {
                // More simplification to be gained here using the Grails REST plugin.
                def sch = new Scheme("https", port?:443 as int, socketFactory)
                client.getConnectionManager().getSchemeRegistry().register(sch)

                def get = new HttpGet(mpUrl + "/public/descriptor/${guid}")
                def response = client.execute(get)
                def header = response.entity?.contentType

                if (header?.value.contains("json")) {
                    log.info "Received JSON response from MP (${mpUrl}); success"
                    def handler = new BasicResponseHandler()
                    def strJson = handler.handleResponse(response)
                    ompObj = JSON.parse(strJson)
                } else {
                    log.warn "Received non-parseable response from MP, content type -> ${response.entity.contentType}"
                }
            } catch (IOException ioE) {
                // Log and eat
                log.warn "The following stack trace may not actually denote an error and comes from an attempted sync with a marketplace instance (${mpUrl})"
                log.warn ioE
            } catch (ClientProtocolException cpE) {
                // Log and eat
                log.warn "The following stack trace may not actually denote an error and comes from an attempted sync with a marketplace instance (${mpUrl})"
                log.warn cpE
            } catch (HttpResponseException hrE) {
                // Log and eat
                log.warn "The following stack trace may not actually denote an error and comes from an attempted sync with a marketplace instance (${mpUrl})"
                log.warn hrE
            } finally {
                client.getConnectionManager().shutdown()
            }

            // Break out on first match
            if (ompObj) {
                return true
            }
        }
        if (ompObj) {
            return ompObj?.data[0]
        }
        else {
            return null
        }
    }

    private SSLSocketFactory createSocketFactory()
    {
        // Some initial setup pertaining to getting certs ready for
        // use (presuming SSL mutual handshake between servers).
        // Here is where the Grails REST plugin would really come to
        // the rescue -- the elimination of so much boilerplate.
        def keyStoreFileName = System.properties['javax.net.ssl.keyStore']
        def keyStorePw = System.properties['javax.net.ssl.keyStorePassword']
        def keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        def trustStoreFileName = System.properties['javax.net.ssl.trustStore']
        def trustStorePw = System.properties['javax.net.ssl.trustStorePassword']
        def trustStore = KeyStore.getInstance(KeyStore.getDefaultType())

        def trustStream = new FileInputStream(new File(trustStoreFileName))
        trustStore.load(trustStream, trustStorePw.toCharArray())
        trustStream.close()

        def keyStream = new FileInputStream(new File(keyStoreFileName))
        keyStore.load(keyStream, keyStorePw.toCharArray())
        keyStream.close()

        def factory = new SSLSocketFactory(keyStore, keyStorePw, trustStore)
        if (config.owf.mpSync.trustAllCerts) {
            def trustAllCerts = new TrustStrategy() {
                boolean isTrusted(X509Certificate[] chain, String authType) {
                    return true
                }
            }
            factory = new SSLSocketFactory(trustAllCerts, new AllowAllHostnameVerifier())
            log.warn "getObjectListingFromMarketplace: trusting all SSL hosts and certificates"
        }

        return factory
    }
}