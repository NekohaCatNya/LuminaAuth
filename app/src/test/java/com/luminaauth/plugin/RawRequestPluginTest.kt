package com.luminaauth.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawRequestPluginTest {
    @Test
    fun rawRequestBlockIsParsed() {
        val yaml = """
            id: raw-test
            sandbox:
              hosts:
                - 218.6.130.195:801
            fields:
              - id: username
                name: 用户名
                type: text
                required: true
              - id: password
                name: 密码
                type: password
                required: true
            check:
              request: |
                GET /chk HTTP/1.1
                Host: 218.6.130.195:801
                Accept-Encoding: gzip, deflate
                Referer: http://218.6.130.195:1333/
              online_when:
                contains: "ok"
              offline_when:
                contains: "no"
            login:
              steps:
                - name: auth
                  request: |
                    POST /login HTTP/1.1
                    Host: 218.6.130.195:801
                    Content-Type: application/x-www-form-urlencoded
                    Accept-Encoding: gzip, deflate

                    user=${'$'}{username}&p=${'$'}{password}
                  success_when:
                    contains: "ok"
        """.trimIndent()
        val plugin = PluginLoader.load(yaml, "raw.plugin.yaml", PluginSource.EXTERNAL)
        val check = plugin.check!!
        assertEquals("GET", check.method)
        assertEquals("http://218.6.130.195:801/chk", check.url)
        assertEquals("gzip, deflate", check.headers["Accept-Encoding"])
        assertNull(check.body)
        val step = plugin.loginSteps[0]
        assertEquals("POST", step.method)
        assertEquals("http://218.6.130.195:801/login", step.url)
        assertTrue(step.body!!.contains("user=\${username}&p=\${password}"))
        assertEquals("gzip, deflate", step.headers["Accept-Encoding"])
    }
}
