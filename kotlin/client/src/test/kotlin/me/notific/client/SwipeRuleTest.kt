package me.notific.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.notific.client.SwipeRule.Intent
import me.notific.client.SwipeRule.Kind

/** O arrasto por tipo (docs/27, D9): a regra que escolhe a ação do arrasto. */
class SwipeRuleTest {
    private fun n(
        title: String = "Pedido 123 enviado",
        summary: String? = "Chega amanhã",
        type: String = "pedido",
        severity: Severity = Severity.Info,
        qs: String? = null,
        content: NotificationContent? = null,
    ) = Notification(
        id = "n1",
        workspaceId = "w1",
        source = "loja",
        type = type,
        severity = severity,
        title = title,
        summary = summary,
        questionSetId = qs,
        content = content,
    )

    @Test
    fun simplesQueCabeMarcaComoLida() {
        assertEquals(Intent.Read, SwipeRule.intent(Kind.Plain, overflows = false, hasMore = false))
    }

    @Test
    fun simplesJaLidaVoltaANaoLida() {
        assertEquals(Intent.Unread, SwipeRule.intent(Kind.Plain, overflows = false, hasMore = false, read = true))
    }

    @Test
    fun simplesCortadaAbre() {
        assertEquals(Intent.Open, SwipeRule.intent(Kind.Plain, overflows = true, hasMore = false))
        assertEquals(Intent.Open, SwipeRule.intent(Kind.Plain, overflows = true, hasMore = false, read = true))
    }

    @Test
    fun simplesComConteudoPorTrasAbre() {
        assertEquals(Intent.Open, SwipeRule.intent(Kind.Plain, overflows = false, hasMore = true))
    }

    @Test
    fun decisaoSempreAbreNuncaResponde() {
        for (overflows in listOf(false, true)) for (more in listOf(false, true)) for (read in listOf(false, true)) {
            assertEquals(Intent.Open, SwipeRule.intent(Kind.Decision, overflows, more, read))
        }
    }

    @Test
    fun cienteDaACienciaMesmoComTextoCortado() {
        assertEquals(Intent.Ack, SwipeRule.intent(Kind.Ack, overflows = false, hasMore = false))
        assertEquals(Intent.Ack, SwipeRule.intent(Kind.Ack, overflows = true, hasMore = true))
    }

    @Test
    fun cienteComVerificacaoAbreOFluxoNormal() {
        assertEquals(Intent.Open, SwipeRule.intent(Kind.Ack, overflows = false, hasMore = false, verify = true))
    }

    @Test
    fun tipoDaNotificacao() {
        assertEquals(Kind.Plain, SwipeRule.kindOf(n(), waitsForAck = false))
        assertEquals(Kind.Decision, SwipeRule.kindOf(n(qs = "qs1"), waitsForAck = false))
        // Uma decisão crítica continua decisão: abre para decidir.
        assertEquals(Kind.Decision, SwipeRule.kindOf(n(qs = "qs1", severity = Severity.Critical), waitsForAck = true))
        assertEquals(Kind.Ack, SwipeRule.kindOf(n(severity = Severity.Critical), waitsForAck = true))
        // Ciente já dado: volta a ser simples.
        assertEquals(Kind.Plain, SwipeRule.kindOf(n(severity = Severity.Critical), waitsForAck = false))
    }

    @Test
    fun conteudoPorTras() {
        assertFalse(SwipeRule.hasMore(n()))
        // Um card só com o texto que a linha já mostra não é conteúdo a mais.
        assertFalse(SwipeRule.hasMore(n(content = NotificationContent(card = Card(blocks = listOf(Block.Text("Chega  amanhã")))))))
        // A mensagem inteira no card, maior que o resumo.
        assertTrue(SwipeRule.hasMore(n(type = "message", content = NotificationContent(card = Card(blocks = listOf(Block.Text("Chega amanhã, e depois mais coisas")))))))
        // Blocos além do texto, ações e HTML.
        assertTrue(SwipeRule.hasMore(n(content = NotificationContent(card = Card(blocks = listOf(Block.Image("https://x/y.png")))))))
        assertTrue(SwipeRule.hasMore(n(content = NotificationContent(card = Card(blocks = listOf(Block.Buttons(listOf(CardButton(label = "OK", action = "ok")))))))))
        assertTrue(SwipeRule.hasMore(n(content = NotificationContent(rich = RichContent("content:c1")))))
        // Resumo "Título: texto" e o mesmo texto no card (como manda o home lab): nada a mais.
        assertFalse(SwipeRule.hasMore(n(title = "Boleto 50153 vencido", summary = "Boleto 50153 vencido: R$ 361,00 venceu há 1 dias.",
            content = NotificationContent(card = Card(blocks = listOf(Block.Text("R$ 361,00 venceu há 1 dias.")))))))
        // Texto do card que não está no resumo continua sendo conteúdo a mais.
        assertTrue(SwipeRule.hasMore(n(content = NotificationContent(card = Card(blocks = listOf(Block.Text("Outra coisa")))))))
        // Só um resumo curto, sem card: nada por trás.
        assertFalse(SwipeRule.hasMore(n(content = NotificationContent(summary = "Chega amanhã"))))
    }
}
