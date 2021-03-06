package com.kotlinbyte.scoped_state

import androidx.lifecycle.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.filter
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import androidx.lifecycle.Lifecycle

class StateWatcher() {

    companion object {
        fun <S : Any> watch(
            flow: StateFlow<ScopedState<TypeMatcher<S, S>, BaseState>>,
            watch: ScopeBuilder<S>.() -> Unit
        ) =
            ScopeBuilder(flow).apply(watch)
    }


    class ScopeBuilder<SCOPE> internal constructor(
        private val flow: StateFlow<ScopedState<TypeMatcher<SCOPE, SCOPE>, BaseState>>
    ) : DefaultLifecycleObserver {

        internal var currentScope: AtomicReference<TypeMatcher<SCOPE, SCOPE>?>? =
            AtomicReference<TypeMatcher<SCOPE, SCOPE>?>()

        override fun onDestroy(owner: LifecycleOwner) {
            super.onDestroy(owner)
            currentScope = null
        }

        val currentScopeState
            get() = currentScope!!.get()?.let {
                scopeDefinitions[it]
            } ?: throw UnknownScopeException()

        internal val scopeDefinitions =
            mutableMapOf<TypeMatcher<SCOPE, SCOPE>, Scope<out BaseState>>()

        fun attach(lifecycle: Lifecycle) {
            lifecycle.addObserver(this)
            lifecycle.coroutineScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    flow.buffer().collect { either ->
                        triggerEither(either)
                    }
                }
            }
        }

        inline fun <reified S : SCOPE> scopeConcrete(): TypeMatcher<SCOPE, S> = TypeMatcher.create()

        fun <SC : SCOPE, STATE : BaseState> scope(
            concrete: TypeMatcher<SCOPE, SC>,
            scope: Scope<STATE>.() -> Unit
        ) {
            scopeDefinitions[concrete] = Scope<STATE>().apply(scope)
        }

        inline fun <reified S : SCOPE, STATE : BaseState> scope(noinline scope: Scope<STATE>.() -> Unit) {
            scope(scopeConcrete<S>(), scope)
        }

        fun <S : SCOPE> trigger(scope: TypeMatcher<SCOPE, S>) {
            currentScope!!.set(scope)
        }

        inline fun <reified S : SCOPE> trigger() {
            trigger(scopeConcrete<S>())
        }

        fun <S : SCOPE> triggerEither(either: ScopedState<TypeMatcher<SCOPE, S>, BaseState>) =
            either.fold(
                {
                    trigger(it)
                },
                { state ->
                    currentScopeState.triggerState(state)
                    Any()
                },
                { mScope, mState ->
                    triggerBoth(mScope, mState)
                }
            )


        class Scope<STATE> internal constructor() {
            val stateDefinitions = mutableMapOf<TypeMatcher<STATE, STATE>, (STATE) -> Unit>()
            inline fun <reified E : STATE> concrete(): TypeMatcher<STATE, E> = TypeMatcher.create()


            fun <E : STATE> state(generic: TypeMatcher<STATE, E>, stateBlock: (E) -> Unit) {
                stateDefinitions[generic] = {
                    @Suppress("UNCHECKED_CAST")
                    stateBlock(it as E)
                }
            }

            inline fun <reified E : STATE> state(noinline stateBlock: (E) -> Unit) {
                state(concrete(), stateBlock)
            }

            fun <E> triggerState(state: E) {
                stateDefinitions.filter {
                    @Suppress("UNCHECKED_CAST")
                    it.key.matches(state as STATE)
                }.firstNotNullOfOrNull {
                    it.value
                }?.let {
                    @Suppress("UNCHECKED_CAST")
                    it.invoke(state as STATE)
                }
            }
        }
    }

    interface BaseState

}


fun <SCOPE, S : SCOPE> StateWatcher.ScopeBuilder<SCOPE>.triggerBoth(
    typeMatcher: TypeMatcher<SCOPE, S>, state: StateWatcher.BaseState
) {
    trigger(typeMatcher)
    currentScopeState.triggerState(state)
}

inline fun <SCOPE, reified S : SCOPE> StateWatcher.ScopeBuilder<SCOPE>.triggerBoth(
    state: StateWatcher.BaseState
) {
    trigger<S>()
    currentScopeState.triggerState(state)
}
