package dev.handypvz

object OzonCodeExtractor {

    const val LOGIN_MARKER = "__LOGIN__"
    const val CHALLENGE_MARKER = "__CHALLENGE__"
    // Treat a whole run of digits as one candidate, so a longer number cannot be truncated.
    private const val CANDIDATE_PATTERN = """\d(?:[\t \u00a0\u202f]*\d)*[\t \u00a0\u202f]*\*[\t \u00a0\u202f]*\d(?:[\t \u00a0\u202f]*\d)*"""
    private const val CODE_PATTERN = """^\d{8,9}\*\d{4}$"""
    private val candidates = Regex(CANDIDATE_PATTERN)
    private val codePattern = Regex(CODE_PATTERN)
    private val spaces = Regex("""[\t \u00a0\u202f]+""")

    const val JS = """
        (function() {
            function findCode(text) {
                var re = /$CANDIDATE_PATTERN/g;
                var valid = /$CODE_PATTERN/;
                var match;
                while ((match = re.exec(text)) !== null) {
                    var code = match[0].replace(/\s+/g, '');
                    if (valid.test(code)) return code;
                }
                return '';
            }
            var nodes = document.querySelectorAll('span.tsHeadline500Medium');
            for (var i = 0; i < nodes.length; i++) {
                var code = findCode(nodes[i].textContent || '');
                if (code) return code;
            }
            var text = document.body ? document.body.innerText : '';
            var code = findCode(text);
            if (code) return code;

            if (/(робот|captcha|antibot|challenge)/i.test(text)) return '__CHALLENGE__';

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
        object Challenge : Extract()
        object None : Extract()
    }

    fun parse(raw: String): Extract {
        val code = candidates.findAll(raw)
            .map { it.value.replace(spaces, "") }
            .firstOrNull { codePattern.matches(it) }
        if (code != null) return Extract.Code(code)
        if (raw.contains(LOGIN_MARKER)) return Extract.Login
        if (raw.contains(CHALLENGE_MARKER)) return Extract.Challenge
        return Extract.None
    }
}
