package com.arelove.app.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.arelove.app.R
import com.arelove.app.databinding.FragmentLikesBinding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Fragment principal de Likes con dos pestañas: Likes recibidos y Matches.
 */
class LikesFragment : Fragment() {

    private var _binding: FragmentLikesBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewPager2: ViewPager2
    private lateinit var tabLayout: TabLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLikesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewPager2 = binding.likesPager
        tabLayout = binding.tabLayout

        // Configurar el ViewPager2 con dos fragments
        val adapter = LikesPagerAdapter(childFragmentManager, lifecycle)
        viewPager2.adapter = adapter

        // Vincular TabLayout con ViewPager2
        TabLayoutMediator(tabLayout, viewPager2) { tab, position ->
            when (position) {
                0 -> tab.text = getString(R.string.likes_tab_received)
                1 -> tab.text = getString(R.string.likes_tab_matches)
            }
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Adaptador de fragmentos para el ViewPager2 de LikesFragment.
     */
    private class LikesPagerAdapter(
        fragmentManager: androidx.fragment.app.FragmentManager,
        lifecycle: androidx.lifecycle.Lifecycle
    ) : androidx.fragment.app.FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> LikesReceivedFragment()
                1 -> MatchesListFragment()
                else -> throw IllegalArgumentException("Posición inválida: $position")
            }
        }
    }
}