package com.siwel.siwel.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siwel.siwel.firebase.MemberPresence
import com.siwel.siwel.firebase.PresenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val presenceRepo = PresenceRepository()

    private val _presences = MutableStateFlow<List<MemberPresence>>(emptyList())
    val presences: StateFlow<List<MemberPresence>> = _presences.asStateFlow()

    private var currentUser: String = ""

    fun init(name: String, allMembers: List<String>) {
        currentUser = name
        presenceRepo.startPresence(name)
        viewModelScope.launch {
            presenceRepo.observeAllPresences(allMembers).collect {
                _presences.value = it
            }
        }
    }

    fun logout() {
        if (currentUser.isNotEmpty()) {
            presenceRepo.stopPresence(currentUser)
        }
    }

    override fun onCleared() {
        super.onCleared()
        logout()
    }
}