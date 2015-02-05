/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.artefact.controller.support

import grails.async.Promise
import grails.converters.JSON
import grails.io.IOUtils
import grails.plugins.GrailsPlugin
import grails.plugins.GrailsPluginManager
import grails.util.GrailsStringUtils
import grails.util.GrailsWebUtil
import grails.web.JSONBuilder
import grails.web.api.WebAttributes
import grails.web.http.HttpHeaders
import grails.web.mime.MimeType
import grails.web.mime.MimeUtility
import groovy.text.Template
import groovy.transform.CompileStatic
import groovy.util.slurpersupport.GPathResult
import groovy.xml.StreamingMarkupBuilder
import org.codehaus.groovy.grails.web.metaclass.ControllerDynamicMethods
import org.grails.gsp.GroovyPageTemplate
import org.grails.gsp.ResourceAwareTemplateEngine
import org.grails.io.support.GrailsResourceUtils
import org.grails.io.support.SpringIOUtils
import org.grails.web.converters.Converter
import org.grails.web.json.JSONElement
import org.grails.web.servlet.mvc.ActionResultTransformer
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.servlet.mvc.exceptions.ControllerExecutionException
import org.grails.web.servlet.view.GroovyPageView
import org.grails.web.sitemesh.GrailsLayoutDecoratorMapper
import org.grails.web.sitemesh.GrailsLayoutView
import org.grails.web.sitemesh.GroovyPageLayoutFinder
import org.grails.web.util.GrailsApplicationAttributes
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.View

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static org.grails.plugins.web.controllers.metaclass.RenderDynamicMethod.*
/**
 *
 * A trait that adds behavior to allow rendering of objects to the response
 * 
 * @author Jeff Brown
 * @author Graeme Rocher
 *
 * @since 3.0
 */
@CompileStatic
trait ResponseRenderer extends WebAttributes {

    private Collection<ActionResultTransformer> actionResultTransformers = []


    private MimeUtility mimeUtility
    private GroovyPageLayoutFinder groovyPageLayoutFinder
    private GrailsPluginManager pluginManager

    @Autowired
    void setGroovyPageLayoutFinder(GroovyPageLayoutFinder groovyPageLayoutFinder) {
        this.groovyPageLayoutFinder = groovyPageLayoutFinder
    }

    @Autowired
    void setMimeUtility(MimeUtility mimeUtility) {
        this.mimeUtility = mimeUtility
    }

    @Autowired(required = false)
    void setActionResultTransformers(ActionResultTransformer[] actionResultTransformers) {
        this.actionResultTransformers = actionResultTransformers.toList()
    }

    /**
     * Render the given object to the response
     *
     * @param object The object to render
     */
    void render(object) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.getCurrentResponse()
        webRequest.setRenderView false

        try {
            response.writer.write object.inspect()
        }
        catch (IOException e) {
            throw new ControllerExecutionException("I/O error obtaining response writer: " + e.getMessage(), e)
        }
    }

    /**
     * Render the given {@link JSONElement} to the response
     *
     * @param json The JSON to render
     */
    void render(JSONElement json) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.getCurrentResponse()

        response.setContentType GrailsWebUtil.getContentType("application/json", DEFAULT_ENCODING)
        renderWritable json, response
    }

    /**
     * Use the given closure to render markup to the response. The markup is assumed to be HTML. Use {@link ResponseRenderer#render(java.util.Map, groovy.lang.Closure)} to change the content type.
     *
     * @param closure The markup to render
     */
    void render(Closure closure) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.getCurrentResponse()

        setContentType response, TEXT_HTML, DEFAULT_ENCODING, true

        renderMarkupInternal(webRequest, closure, response)
    }

    /**
     * Render the given closure, configured by the named argument map, to the response
     *
     * @param argMap The name arguments
     * @param closure The closure to render
     */
    void render(Map argMap, Closure closure) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.getCurrentResponse()
        String explicitSiteMeshLayout = argMap[ARGUMENT_LAYOUT]?.toString() ?: null

        applyContentType response, argMap, closure
        if (BUILDER_TYPE_JSON.equals(argMap.get(ARGUMENT_BUILDER)) || isJSONResponse(response)) {
            JSONBuilder builder = new JSONBuilder()
            JSON json = builder.build(closure)
            json.render response
            webRequest.setRenderView false
        }
        else {
            renderMarkupInternal(webRequest, closure, response)
        }
        applySiteMeshLayout webRequest.getCurrentRequest(), false, explicitSiteMeshLayout
    }

    /**
     * Render the given CharSequence to the response with the give named arguments
     *
     * @param argMap The named arguments
     * @param body The text to render
     */
    void render(Map argMap, CharSequence body) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.getCurrentResponse()
        String explicitSiteMeshLayout = argMap[ARGUMENT_LAYOUT]?.toString() ?: null

        handleStatusArgument argMap, webRequest, response
        render body
        applySiteMeshLayout webRequest.getCurrentRequest(), false, explicitSiteMeshLayout
    }

    /**
     * Render the given converter to the response
     *
     * @param converter The converter to render
     */
    void render(Converter<?> converter) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.currentResponse
        webRequest.setRenderView false
        converter.render response
    }

    /**
     * Renders text to the response for the given CharSequence
     *
     * @param txt The text to render
     */
    void render(CharSequence txt) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.getCurrentResponse()

        try {
            PrintWriter writer = response.getWriter()
            if (writer instanceof PrintWriter) {
                ((PrintWriter)writer).print(txt)
            }
            else {
                writer.write(txt.toString())
            }
            writer.flush()
            webRequest.setRenderView false
        }
        catch (IOException e) {
            throw new ControllerExecutionException(e.getMessage(), e)
        }
    }

    /**
     * Render the given writable to the response using the named arguments to configure the response
     *
     * @param argMap The named arguments
     * @param writable The writable
     */
    void render(Map argMap, Writable writable) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.currentResponse
        String explicitSiteMeshLayout = argMap[ARGUMENT_LAYOUT]?.toString() ?: null

        handleStatusArgument argMap, webRequest, response
        applyContentType response, argMap, writable
        renderWritable writable, response
        applySiteMeshLayout webRequest.getCurrentRequest(), false, explicitSiteMeshLayout
        webRequest.renderView = false
    }

    /**
     * Render a response for the given named arguments
     *
     * @param argMap The named argument map
     */
    void render(Map argMap) {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes()
        HttpServletResponse response = webRequest.getCurrentResponse()
        String explicitSiteMeshLayout = argMap[ARGUMENT_LAYOUT]?.toString() ?: null
        handleStatusArgument(argMap, webRequest, response)

        if (argMap.containsKey(ARGUMENT_TEXT)) {
            def textArg = argMap.get(ARGUMENT_TEXT)
            applyContentType response, argMap, textArg
            if (textArg instanceof Writable) {
                renderWritable((Writable)textArg, response)
                webRequest.renderView = false
            } else {
                CharSequence text = (textArg instanceof CharSequence) ? ((CharSequence)textArg) : textArg.toString()
                render text
            }
            applySiteMeshLayout webRequest.getCurrentRequest(), false, explicitSiteMeshLayout
        }
        else if (argMap.containsKey(ARGUMENT_VIEW)) {
            String viewName = argMap.get(ARGUMENT_VIEW).toString()
            String viewUri = webRequest.getAttributes().getNoSuffixViewURI((GroovyObject)this, viewName)
            String contextPath = getContextPath(webRequest, argMap)
            if(contextPath != null) {
                viewUri = contextPath + viewUri
            }
            Object modelObject = argMap.get(ARGUMENT_MODEL)
            if (modelObject != null) {
                modelObject = argMap.get(ARGUMENT_MODEL)
                boolean isPromise = modelObject instanceof Promise
                Collection<ActionResultTransformer> resultTransformers = actionResultTransformers
                for (ActionResultTransformer resultTransformer : resultTransformers) {
                    modelObject = resultTransformer.transformActionResult(webRequest,viewUri, modelObject)
                }
                if (isPromise) return
            }

            applyContentType webRequest.getCurrentResponse(), argMap, null

            Map model
            if (modelObject instanceof Map) {
                model = (Map) modelObject
            }
            else {
                model = [:]
            }

            ((GroovyObject)this).setProperty ControllerDynamicMethods.MODEL_AND_VIEW_PROPERTY, new ModelAndView(viewUri, model)
            applySiteMeshLayout webRequest.getCurrentRequest(), true, explicitSiteMeshLayout
        }
        else if (argMap.containsKey(ARGUMENT_TEMPLATE)) {
            applyContentType response, argMap, null, false
            webRequest.renderView = false
            boolean hasModel = argMap.containsKey(ARGUMENT_MODEL)
            def modelObject
            if(hasModel) {
                modelObject = argMap.get(ARGUMENT_MODEL)
            }
            String templateName = argMap.get(ARGUMENT_TEMPLATE).toString()
            String contextPath = getContextPath(webRequest, argMap)

            String var
            if (argMap.containsKey(ARGUMENT_VAR)) {
                var = String.valueOf(argMap.get(ARGUMENT_VAR))
            }

            // get the template uri
            String templateUri = webRequest.getAttributes().getTemplateURI((GroovyObject)this, templateName)

            // retrieve gsp engine
            ResourceAwareTemplateEngine engine = webRequest.getAttributes().getPagesTemplateEngine()
            try {
                Template t = engine.createTemplateForUri([
                        GrailsResourceUtils.appendPiecesForUri(contextPath, templateUri),
                        GrailsResourceUtils.appendPiecesForUri(contextPath, "/grails-app/views/", templateUri)] as String[]);

                if (t == null) {
                    throw new ControllerExecutionException("Unable to load template for uri [$templateUri]. Template not found.")
                }

                if (t instanceof GroovyPageTemplate) {
                    ((GroovyPageTemplate)t).setAllowSettingContentType(true)
                }

                GroovyPageView gspView = new GroovyPageView()
                gspView.template = t

                try {
                    gspView.afterPropertiesSet()
                } catch (Exception e) {
                    throw new RuntimeException("Problem initializing view", e)
                }

                View view = gspView
                boolean renderWithLayout = (explicitSiteMeshLayout || webRequest.getCurrentRequest().getAttribute(GrailsLayoutDecoratorMapper.LAYOUT_ATTRIBUTE))
                if(renderWithLayout) {
                    applySiteMeshLayout webRequest.getCurrentRequest(), false, explicitSiteMeshLayout
                    try {
                        view = new GrailsLayoutView(groovyPageLayoutFinder, gspView)
                    } catch (NoSuchBeanDefinitionException e) {
                        // ignore
                    }
                }

                Map binding = [:]

                if (argMap.containsKey(ARGUMENT_BEAN)) {
                    Object bean = argMap.get(ARGUMENT_BEAN)
                    if (hasModel) {
                        if (modelObject instanceof Map) {
                            setTemplateModel(webRequest, binding, (Map) modelObject)
                        }
                    }
                    if (GrailsStringUtils.isBlank(var)) {
                        binding.put DEFAULT_ARGUMENT, bean
                    }
                    else {
                        binding.put var, bean
                    }
                    renderViewForTemplate webRequest, view, binding
                }
                else if (argMap.containsKey(ARGUMENT_COLLECTION)) {
                    Object colObject = argMap.get(ARGUMENT_COLLECTION)
                    if (hasModel) {
                        if (modelObject instanceof Map) {
                            setTemplateModel webRequest, binding, (Map)modelObject
                        }
                    }
                    renderTemplateForCollection webRequest, view, binding, colObject, var
                }
                else if (hasModel) {
                    if (modelObject instanceof Map) {
                        setTemplateModel webRequest, binding, (Map)modelObject
                    }
                    renderViewForTemplate webRequest, view, binding
                }
                else {
                    renderViewForTemplate webRequest, view, binding
                }
            }
            catch (GroovyRuntimeException gre) {
                throw new ControllerExecutionException("Error rendering template [" + templateName + "]: " + gre.getMessage(), gre)
            }
            catch (IOException ioex) {
                throw new ControllerExecutionException("I/O error executing render method for arguments [" + argMap + "]: " + ioex.getMessage(), ioex)
            }
        }
        else if (argMap.containsKey(ARGUMENT_FILE)) {
            webRequest.renderView = false

            def o = argMap.get(ARGUMENT_FILE)
            def fnO = argMap.get(ARGUMENT_FILE_NAME)
            String fileName = fnO != null ? fnO.toString() : ((o instanceof File) ? ((File)o).getName(): null )
            if (o != null) {
                boolean hasContentType = applyContentType(response, argMap, null, false)
                if (fileName != null) {
                    if(!hasContentType) {
                        hasContentType = detectContentTypeFromFileName(webRequest, response, argMap, fileName)
                    }
                    if (fnO != null) {
                        response.setHeader HttpHeaders.CONTENT_DISPOSITION, DISPOSITION_HEADER_PREFIX + fileName
                    }
                }
                if (!hasContentType) {
                    throw new ControllerExecutionException(
                            "Argument [file] of render method specified without valid [contentType] argument")
                }

                InputStream input
                try {
                    if (o instanceof File) {
                        input = IOUtils.openStream(o)
                    }
                    else if (o instanceof InputStream) {
                        input = (InputStream)o
                    }
                    else if (o instanceof byte[]) {
                        input = new ByteArrayInputStream((byte[])o)
                    }
                    else {
                        input = IOUtils.openStream(new File(o.toString()))
                    }
                    SpringIOUtils.copy input, response.getOutputStream()
                } catch (IOException e) {
                    throw new ControllerExecutionException(
                            "I/O error copying file to response: " + e.getMessage(), e)
                }
                finally {
                    if (input != null) {
                        try {
                            input.close()
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                }
            }
        }
    }



    private void handleStatusArgument(Map argMap, GrailsWebRequest webRequest, HttpServletResponse response) {
        boolean statusSet
        if (argMap.containsKey(ARGUMENT_STATUS)) {
            def statusObj = argMap.get(ARGUMENT_STATUS)
            if (statusObj != null) {
                try {
                    final int statusCode = statusObj instanceof Number ? ((Number) statusObj).intValue() : Integer.parseInt(statusObj.toString())
                    response.setStatus statusCode
                    statusSet = true
                }
                catch (NumberFormatException e) {
                    throw new ControllerExecutionException(
                            "Argument [status] of method [render] must be a valid integer.")
                }
            }
        }
        if(statusSet) {
            webRequest.renderView = false
        }
    }

    private void renderMarkupInternal(GrailsWebRequest webRequest, Closure closure, HttpServletResponse response) {
        StreamingMarkupBuilder b = new StreamingMarkupBuilder()
        b.encoding = response.characterEncoding

        Writable markup = (Writable) b.bind(closure)
        renderWritable markup, response

        webRequest.setRenderView false
    }

    private boolean isJSONResponse(HttpServletResponse response) {
        String contentType = response.getContentType()
        return contentType != null && (contentType.indexOf("application/json") > -1 ||
                contentType.indexOf("text/json") > -1)
    }

    private void renderWritable(Writable writable, HttpServletResponse response) {
        try {
            PrintWriter writer = response.getWriter()
            writable.writeTo writer
            writer.flush()
        }
        catch (IOException e) {
            throw new ControllerExecutionException(e.getMessage(), e)
        }
    }

    private boolean applyContentType(HttpServletResponse response, Map argMap, Object renderArgument) {
        applyContentType response, argMap, renderArgument, true
    }

    private boolean applyContentType(HttpServletResponse response, Map argMap, Object renderArgument, boolean useDefault) {
        boolean contentTypeIsDefault = true
        String contentType = resolveContentTypeBySourceType(renderArgument, useDefault ? TEXT_HTML : null)
        String encoding = DEFAULT_ENCODING
        if (argMap != null) {
            if(argMap.containsKey(ARGUMENT_CONTENT_TYPE)) {
                contentType = argMap.get(ARGUMENT_CONTENT_TYPE).toString()
                contentTypeIsDefault = false
            }
            if(argMap.containsKey(ARGUMENT_ENCODING)) {
                encoding = argMap.get(ARGUMENT_ENCODING).toString()
                contentTypeIsDefault = false
            }
        }
        if(contentType != null) {
            setContentType response, contentType, encoding, contentTypeIsDefault
            return true
        }
        false
    }

    private void setContentType(HttpServletResponse response, String contentType, String encoding) {
        setContentType response, contentType, encoding, false
    }

    private void setContentType(HttpServletResponse response, String contentType, String encoding, boolean contentTypeIsDefault) {
        if (!contentTypeIsDefault || response.getContentType()==null) {
            response.setContentType GrailsWebUtil.getContentType(contentType, encoding)
        }
    }

    private String resolveContentTypeBySourceType(final Object renderArgument, String defaultEncoding) {
        renderArgument instanceof GPathResult ? APPLICATION_XML : defaultEncoding
    }

    private void applySiteMeshLayout(HttpServletRequest request, boolean renderView, String explicitSiteMeshLayout) {
        if(explicitSiteMeshLayout == null && request.getAttribute(GrailsLayoutDecoratorMapper.LAYOUT_ATTRIBUTE) != null) {
            // layout has been set already
            return
        }
        String siteMeshLayout = explicitSiteMeshLayout != null ? explicitSiteMeshLayout : (renderView ? null : GrailsLayoutDecoratorMapper.NONE_LAYOUT)
        if(siteMeshLayout != null) {
            request.setAttribute GrailsLayoutDecoratorMapper.LAYOUT_ATTRIBUTE, siteMeshLayout
        }
    }

    private String getContextPath(GrailsWebRequest webRequest, Map argMap) {
        def cp = argMap.get(ARGUMENT_CONTEXTPATH)
        String contextPath = (cp != null ? cp.toString() : "")

        Object pluginName = argMap.get(ARGUMENT_PLUGIN)
        if (pluginName != null) {

            GrailsPlugin plugin = getPluginManager(webRequest).getGrailsPlugin(pluginName.toString())
            if (plugin != null && !plugin.isBasePlugin()) contextPath = plugin.getPluginPath()
        }
        contextPath
    }

    private GrailsPluginManager getPluginManager(GrailsWebRequest webRequest) {
        if(pluginManager == null) {
            pluginManager = webRequest.getApplicationContext().getBean(GrailsPluginManager)
        }
        pluginManager
    }

    private void setTemplateModel(GrailsWebRequest webRequest, Map binding, Map modelObject) {
        Map modelMap = modelObject
        webRequest.setAttribute GrailsApplicationAttributes.TEMPLATE_MODEL, modelMap, RequestAttributes.SCOPE_REQUEST
        binding.putAll modelMap
    }

    private void renderTemplateForCollection(GrailsWebRequest webRequest, View view, Map binding, Object colObject, String var) throws IOException {
        if (colObject instanceof Iterable) {
            Iterable c = (Iterable) colObject
            for (Object o : c) {
                if (GrailsStringUtils.isBlank(var)) {
                    binding.put DEFAULT_ARGUMENT, o
                }
                else {
                    binding.put var, o
                }
                renderViewForTemplate webRequest, view, binding
            }
        }
        else {
            if (GrailsStringUtils.isBlank(var)) {
                binding.put DEFAULT_ARGUMENT, colObject
            }
            else {
                binding.put var, colObject
            }

            renderViewForTemplate webRequest, view, binding
        }
    }
    private void renderViewForTemplate(GrailsWebRequest webRequest, View view, Map binding) {
        try {
            view.render binding, webRequest.getCurrentRequest(), webRequest.getResponse()
        }
        catch (Exception e) {
            throw new ControllerExecutionException(e.getMessage(), e)
        }
    }

    private boolean detectContentTypeFromFileName(GrailsWebRequest webRequest, HttpServletResponse response, Map argMap, String fileName) {
        if (mimeUtility) {
            MimeType mimeType = mimeUtility.getMimeTypeForExtension(GrailsStringUtils.getFilenameExtension(fileName))
            if (mimeType) {
                String contentType = mimeType.name
                def encodingObj = argMap.get(ARGUMENT_ENCODING)
                String encoding = encodingObj ? encodingObj.toString() : DEFAULT_ENCODING
                setContentType response, contentType, encoding
                return true
            }
        }
        return false
    }

}
