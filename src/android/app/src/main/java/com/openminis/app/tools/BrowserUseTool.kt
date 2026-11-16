package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool schema definition for browser_use, mirroring the iOS agent tool registration.
 * Used when sending tool definitions to the LLM API.
 */
object BrowserUseTool {

    const val NAME = "browser_use"

    val description = """Control web browser with up to 3 tabs. Actions: navigate to URL, take screenshot, click elements, type text, get page text, scroll, get page info, execute JavaScript, find elements by selector, hover, get readable content, set user agent, get page backbone (DOM structure), fetch resource, manage tabs (new_tab, close_tab, list_tabs), manage cookies (get_cookies, set_cookies, clear_cookies), form automation (fill_form, submit_form), file upload (upload_file), download management (list_downloads, cancel_download), console logs (get_console_logs), network requests (get_network_requests), save as PDF (save_as_pdf).""".trimIndent()

    /**
     * Build the JSON tool definition for the Anthropic / OpenAI / Gemini API.
     * Returns a JSONObject suitable for the "tools" array in the API request.
     */
    fun toolDefinition(): JSONObject {
        val tool = JSONObject()
        tool.put("name", NAME)
        tool.put("description", description)

        val properties = JSONObject()

        properties.put("tool_title", JSONObject().apply {
            put("type", "string")
            put("description", "5-10 word summary of what this call does")
        })

        properties.put("action", JSONObject().apply {
            put("type", "string")
            put("description", "The browser action to perform")
            put("enum", JSONArray(BrowserAction.allValues))
        })

        properties.put("url", JSONObject().apply {
            put("type", "string")
            put("description", "URL to navigate to or fetch (for navigate/fetch/new_tab)")
        })

        properties.put("selector", JSONObject().apply {
            put("type", "string")
            put("description", "CSS selector for element interaction (click/type/hover/find_elements/get_text/scroll)")
        })

        properties.put("text", JSONObject().apply {
            put("type", "string")
            put("description", "Text to type into the selected element")
        })

        properties.put("coordinate_x", JSONObject().apply {
            put("type", "integer")
            put("description", "X coordinate for click-by-position")
        })

        properties.put("coordinate_y", JSONObject().apply {
            put("type", "integer")
            put("description", "Y coordinate for click-by-position")
        })

        properties.put("direction", JSONObject().apply {
            put("type", "string")
            put("description", "Scroll direction")
            put("enum", JSONArray(listOf("up", "down")))
        })

        properties.put("amount", JSONObject().apply {
            put("type", "integer")
            put("description", "Scroll amount in pixels (default 500)")
        })

        properties.put("script", JSONObject().apply {
            put("type", "string")
            put("description", "JavaScript code to execute (for execute_js). The script runs inside an async function wrapper — await and top-level return are both supported.")
        })

        properties.put("user_agent", JSONObject().apply {
            put("type", "string")
            put("description", "set_user_agent: profile for the target tab only (not saved; other tabs and the Browser Settings default are unchanged). Aliases: desktop, mobile.")
            put("enum", JSONArray(listOf("desktop_chrome", "mobile_chrome")))
        })

        properties.put("max_depth", JSONObject().apply {
            put("type", "integer")
            put("description", "Maximum DOM tree depth for get_backbone (default 5)")
        })

        properties.put("tab_id", JSONObject().apply {
            put("type", "integer")
            put("description", "Tab ID for tab management actions")
        })

        properties.put("full_page", JSONObject().apply {
            put("type", "boolean")
            put("default", false)
            put("description", "When true on screenshot, capture the entire scrollable page by temporarily stretching the viewport to document.scrollHeight (height-capped at 32768 px). Default false captures only the current viewport.")
        })

        // Cookie management
        properties.put("cookies", JSONObject().apply {
            put("type", "array")
            put("description", "set_cookies: array of cookie objects {name, value, domain?, path?, secure?, http_only?, expires?}. get_cookies/clear_cookies: optional filter.")
            put("items", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().put("type", "string"))
                    put("value", JSONObject().put("type", "string"))
                    put("domain", JSONObject().put("type", "string"))
                    put("path", JSONObject().put("type", "string"))
                    put("secure", JSONObject().put("type", "boolean"))
                    put("http_only", JSONObject().put("type", "boolean"))
                    put("expires", JSONObject().put("type", "integer"))
                })
                put("required", JSONArray(listOf("name", "value")))
            })
        })

        // Form automation
        properties.put("fields", JSONObject().apply {
            put("type", "object")
            put("description", "fill_form: mapping of selector -> value for form fields")
            put("additionalProperties", JSONObject().put("type", "string"))
        })

        // File upload
        properties.put("file_path", JSONObject().apply {
            put("type", "string")
            put("description", "upload_file: local file path to upload")
        })

        // Download management
        properties.put("download_id", JSONObject().apply {
            put("type", "string")
            put("description", "cancel_download: ID of download to cancel")
        })

        // Save as PDF
        properties.put("pdf_options", JSONObject().apply {
            put("type", "object")
            put("description", "save_as_pdf: options {paper_size?, margins?, landscape?}")
            put("properties", JSONObject().apply {
                put("paper_size", JSONObject().put("type", "string"))
                put("margins", JSONObject().put("type", "object"))
                put("landscape", JSONObject().put("type", "boolean"))
            })
        })

        val inputSchema = JSONObject()
        inputSchema.put("type", "object")
        inputSchema.put("properties", properties)
        inputSchema.put("required", JSONArray(listOf("tool_title", "action")))

        tool.put("input_schema", inputSchema)
        return tool
    }

    /**
     * Build tool definition for Anthropic API format.
     */
    fun anthropicToolDefinition(): JSONObject = toolDefinition()

    /**
     * Build tool definition for OpenAI API function calling format.
     */
    fun openAIFunctionDefinition(): JSONObject {
        val def = toolDefinition()
        val fn = JSONObject()
        fn.put("type", "function")
        val function = JSONObject()
        function.put("name", def.getString("name"))
        function.put("description", def.getString("description"))
        function.put("parameters", def.getJSONObject("input_schema"))
        fn.put("function", function)
        return fn
    }
}