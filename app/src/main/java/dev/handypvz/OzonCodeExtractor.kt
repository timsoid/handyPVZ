package dev.handypvz

object OzonCodeExtractor {

    const val LOGIN_MARKER = "__LOGIN__"

    const val JS = """
        (function() {
            var re = /\d{4}\s*\d{4}\s*\*\s*\d{4}/;
            var nodes = document.querySelectorAll('span.tsHeadline500Medium');
            for (var i = 0; i < nodes.length; i++) {
                var m = nodes[i].textContent.match(re);
                if (m) return m[0].replace(/\s+/g, '');
            }
            var text = document.body ? document.body.innerText : '';
            var m2 = text.match(re);
            if (m2) return m2[0].replace(/\s+/g, '');

            var els = document.querySelectorAll('a, button');
            for (var j = 0; j < els.length; j++) {
                var t = (els[j].textContent || '').replace(/\s+/g, ' ').trim();
                if (t.indexOf('Войти') !== -1) return '__LOGIN__';
            }
            return '';
        })();
    """

    sealed class Extract {
        data class Code(val value: String) : Extract()
        object Login : Extract()
        object None : Extract()
    }

    fun parse(raw: String): Extract {
        val code = Regex("""\d{4}\s*\d{4}\s*\*\s*\d{4}""")
            .find(raw)?.value?.replace(Regex("""\s+"""), "")
        if (code != null) return Extract.Code(code)
        if (raw.contains(LOGIN_MARKER)) return Extract.Login
        return Extract.None
    }
}
