package com.weike.ime.network

import com.weike.ime.data.LexiconTerm
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.data.PunctuationPreference
import com.weike.ime.data.WritingStyle
import com.weike.ime.workflow.WorkflowTaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Resolve membership and the specific business binding before every AI operation. */
class RoutedTextPolisher(
    private val router: ModelAccessRouter,
    private val factory: (ModelEndpointConfig) -> TextPolisher
) : TextPolisher {
    private suspend fun resolve(feature: ModelFeature) = factory(router.route(feature).endpoint)
    private suspend fun <T> use(feature: ModelFeature, block: suspend (TextPolisher) -> Result<T>): Result<T> = try {
        block(resolve(feature))
    } catch (error: CancellationException) { throw error }
      catch (error: Exception) { Result.failure(error) }

    override suspend fun polishFast(text: String, style: WritingStyle, punctuation: PunctuationPreference, lexicon: List<LexiconTerm>) =
        use(ModelFeature.SUPER_SPEAK) { it.polishFast(text, style, punctuation, lexicon) }
    override suspend fun polish(text: String, style: WritingStyle, punctuation: PunctuationPreference, lexicon: List<LexiconTerm>, optimizeExpression: Boolean, structureHint: Boolean) =
        use(ModelFeature.SUPER_SPEAK) { it.polish(text, style, punctuation, lexicon, optimizeExpression, structureHint) }
    override suspend fun suggestPolishes(text: String, relation: String) =
        use(ModelFeature.SUPER_SPEAK) { it.suggestPolishes(text, relation) }
    override suspend fun suggestReplies(message: String) = use(ModelFeature.REPLY) { it.suggestReplies(message) }
    override fun polishStream(text: String, style: WritingStyle, punctuation: PunctuationPreference, lexicon: List<LexiconTerm>, optimizeExpression: Boolean, structureHint: Boolean): Flow<String> = flow {
        emitAll(resolve(ModelFeature.SUPER_SPEAK).polishStream(text, style, punctuation, lexicon, optimizeExpression, structureHint))
    }
    override suspend fun answer(question: String) = use(ModelFeature.REPLY) { it.answer(question) }
    override fun answerStream(question: String): Flow<String> = flow { emitAll(resolve(ModelFeature.REPLY).answerStream(question)) }
    override suspend fun classifyWorkflow(command: String): Result<WorkflowTaskType> = use(ModelFeature.REPLY) { it.classifyWorkflow(command) }
    override suspend fun editWorkflowText(instruction: String, text: String, style: WritingStyle, punctuation: PunctuationPreference, lexicon: List<LexiconTerm>, optimizeExpression: Boolean) =
        use(ModelFeature.REPLY) { it.editWorkflowText(instruction, text, style, punctuation, lexicon, optimizeExpression) }
    override suspend fun translateToAmericanEnglish(text: String) = use(ModelFeature.SUPER_SPEAK) { it.translateToAmericanEnglish(text) }
}
