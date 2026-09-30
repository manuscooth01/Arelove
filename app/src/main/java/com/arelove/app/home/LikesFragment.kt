package com.arelove.app.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.TabLayoutMediator
import com.arelove.app.R
import com.arelove.app.databinding.FragmentLikesBinding

/**
 * Fragment principal de Likes con 2 tabs: Likes recibidos y Matches.
 */
class LikesFragment : Fragment() {

    private var _binding: FragmentLikesBinding? = null
    private val binding get() = _binding!!

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
        configurarViewPager()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun configurarViewPager() {
        val adapter = LikesPagerAdapter(this)
        binding.likesPager.adapter = adapter
        TabLayoutMediator(binding.tabLayout, binding.likesPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.likes_tab_received)
                1 -> getString(R.string.likes_tab_matches)
                else -> ""
            }
        }.attach()
    }

    inner class LikesPagerAdapter(fragment: Fragment) :
        androidx.fragment.app.FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> LikesReceivedFragment()
            1 -> MatchesListFragment()
            else -> LikesReceivedFragment()
        }
    }
}