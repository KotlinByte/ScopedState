package com.kotlinbyte.scoped_state

import androidx.lifecycle.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.lang.NullPointerException

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
    ) : LifecycleObserver {

        internal var currentScope: TypeMatcher<SCOPE, SCOPE>? = null

        val currentScopeState
            get() = currentScope?.let {
                scopeDefinitions[it]
            } ?: throw NullPointerException()


        internal val scopeDefinitions =
            mutableMapOf<TypeMatcher<SCOPE, SCOPE>, Scope<out BaseState>>()

        fun attach(lifecycle: Lifecycle) {
            lifecycle.addObserver(this)
            lifecycle.coroutineScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    flow.collect { either ->
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
            currentScope = scope
        }

        inline fun <reified S : SCOPE> trigger() {
            trigger(scopeConcrete<S>())
        }

        fun <S : SCOPE> triggerEither(either: ScopedState<TypeMatcher<SCOPE, S>, BaseState>) {
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
        }


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
                }.firstNotNullOf {
                    @Suppress("UNCHECKED_CAST")
                    it.value.invoke(state as STATE)
                }
            }
        }
    }


    class TypeMatcher<P, out D : P> private constructor(private val clazz: Class<D>) {
        private val predicates: (P) -> Boolean = { clazz.isInstance(it) }


        fun matches(value: P) = predicates(value)


        override fun hashCode(): Int {
            return clazz.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            return (hashCode() == other.hashCode())
        }

        companion object {
            fun <P, D : P> create(clazz: Class<D>): TypeMatcher<P, D> = TypeMatcher(clazz)
            inline fun <P, reified D : P> create(): TypeMatcher<P, D> = create(D::class.java)
        }
    }

    interface BaseState

}


fun <SCOPE, S : SCOPE> StateWatcher.ScopeBuilder<SCOPE>.triggerBoth(
    typeMatcher: StateWatcher.TypeMatcher<SCOPE, S>, state: StateWatcher.BaseState
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
