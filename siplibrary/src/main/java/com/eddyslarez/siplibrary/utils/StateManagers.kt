package com.eddyslarez.siplibrary.utils

import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.models.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


//object RegistrationStateManager {
//    private val _registrationStateFlow = MutableStateFlow(RegistrationState.NONE)
//    val registrationStateFlow: StateFlow<RegistrationState> = _registrationStateFlow.asStateFlow()
//
//    fun updateCallState(newState: RegistrationState) {
//        _registrationStateFlow.value = newState
//    }
//
//    fun getCurrentRegistrationState(): RegistrationState = _registrationStateFlow.value
//}